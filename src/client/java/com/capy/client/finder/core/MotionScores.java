package com.capy.client.finder.core;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Per-chunk live activity score with exponential decay and a threshold that adapts to how noisy the server is. */
public final class MotionScores {
	public static final double HALF_LIFE_MS = 30_000.0;

	public enum Level { BUSY, VERY_BUSY }

	private final Map<Long, double[]> cells = new HashMap<>(); // {score, lastMs}

	private static double decayed(double[] c, long now) {
		return decayed(c, now, HALF_LIFE_MS);
	}

	/** Decay against a caller's window instead of the shared thirty seconds. */
	private static double decayed(double[] c, long now, double halfLifeMs) {
		double life = halfLifeMs <= 1.0 ? 1.0 : halfLifeMs;
		return c[0] * Math.pow(0.5, (now - c[1]) / life);
	}

	public synchronized void add(long chunk, double weight, long nowMs) {
		double[] c = cells.computeIfAbsent(chunk, k -> new double[]{0, nowMs});
		c[0] = decayed(c, nowMs) + weight;
		c[1] = nowMs;
	}

	public synchronized double score(long chunk, long nowMs) {
		double[] c = cells.get(chunk);
		return c == null ? 0 : decayed(c, nowMs);
	}

	/** Score with a caller-supplied half-life, for the Memory setting. */
	public synchronized double score(long chunk, long nowMs, double halfLifeMs) {
		double[] c = cells.get(chunk);
		return c == null ? 0 : decayed(c, nowMs, halfLifeMs);
	}

	public synchronized void forget(long chunk) {
		cells.remove(chunk);
	}

	public synchronized void clear() {
		cells.clear();
	}

	/** BUSY above mean + 2 sigma, VERY_BUSY above mean + 4 sigma, never below minScore. */
	public synchronized Map<Long, Level> busy(long nowMs, double minScore) {
		return busy(nowMs, minScore, HALF_LIFE_MS);
	}

	/** The same levels, but decayed over a window the module chose. */
	public synchronized Map<Long, Level> busy(long nowMs, double minScore, double halfLifeMs) {
		Map<Long, Level> out = new HashMap<>();
		double sum = 0, sq = 0;
		int n = 0;
		for (Iterator<Map.Entry<Long, double[]>> it = cells.entrySet().iterator(); it.hasNext(); ) {
			double s = decayed(it.next().getValue(), nowMs, halfLifeMs);
			if (s < 0.05) {
				it.remove();
				continue;
			}
			sum += s;
			sq += s * s;
			n++;
		}
		if (n == 0) return out;
		double mean = sum / n, sigma = Math.sqrt(Math.max(0, sq / n - mean * mean));
		double busy = Math.max(minScore, mean + 2 * sigma), very = Math.max(minScore * 2, mean + 4 * sigma);
		for (Map.Entry<Long, double[]> e : cells.entrySet()) {
			double s = decayed(e.getValue(), nowMs, halfLifeMs);
			if (s >= very) out.put(e.getKey(), Level.VERY_BUSY);
			else if (s >= busy) out.put(e.getKey(), Level.BUSY);
		}
		return out;
	}
}
