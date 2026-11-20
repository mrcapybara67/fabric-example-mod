package com.capy.client.finder.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Live activity per 16x16x16 section with exponential decay (half life 30 s), duplicate suppression, and farm detection:
 * four or more events of one kind at one exact block position with a steady rhythm mean a machine is running.
 */
public final class ActivityGrid {
	public static final double HALF_LIFE_MS = 30_000.0;
	public static final long DEDUPE_MS = 300;

	public record Cell(int sx, int sy, int sz, double score, SoundKinds.Kind dominant, boolean farm, double periodMs,
			double x, double y, double z) {
	}

	private static final class Acc {
		double score;
		long last;
		final double[] byKind = new double[SoundKinds.Kind.values().length];
		long farmSeen = Long.MIN_VALUE / 2;
		double period;
		double lx, ly, lz;
	}

	private static final class Rhythm {
		final long[] t = new long[6];
		int n;
	}

	private final Map<Long, Acc> cells = new HashMap<>();
	private final Map<Long, Rhythm> rhythms = new HashMap<>();
	private final Map<Long, Long> lastSeen = new HashMap<>();

	private static double decay(long now, long last) {
		return decay(now, last, HALF_LIFE_MS);
	}

	private static double decay(long now, long last, double halfLifeMs) {
		double life = halfLifeMs <= 1.0 ? 1.0 : halfLifeMs;
		return Math.pow(0.5, (now - last) / life);
	}

	/** Returns false when the event was a duplicate (same kind and block within 300 ms) and was ignored. */
	public synchronized boolean add(SoundKinds.Kind kind, double weight, double x, double y, double z, long nowMs) {
		if (kind == SoundKinds.Kind.IGNORE || weight <= 0) {
			return false;
		}
		int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
		long posKey = Chunks.block(bx, by, bz) * 8 + kind.ordinal();
		Long seen = lastSeen.get(posKey);
		if (seen != null && nowMs - seen < DEDUPE_MS) {
			return false;
		}
		lastSeen.put(posKey, nowMs);
		if (lastSeen.size() > 8192) {
			lastSeen.keySet().removeIf(k -> nowMs - lastSeen.get(k) > 60_000);
		}
		long cellKey = Chunks.block(bx >> 4, by >> 4, bz >> 4);
		Acc a = cells.computeIfAbsent(cellKey, k -> new Acc());
		double f = decay(nowMs, a.last);
		a.score = a.score * f + weight;
		for (int i = 0; i < a.byKind.length; i++) {
			a.byKind[i] *= f;
		}
		a.byKind[kind.ordinal()] += weight;
		a.last = nowMs;
		a.lx = x;
		a.ly = y;
		a.lz = z;
		Rhythm r = rhythms.computeIfAbsent(posKey, k -> new Rhythm());
		if (r.n == r.t.length) {
			System.arraycopy(r.t, 1, r.t, 0, r.t.length - 1);
			r.n--;
		}
		r.t[r.n++] = nowMs;
		if (r.n >= 4) {
			double mean = (double) (r.t[r.n - 1] - r.t[0]) / (r.n - 1);
			boolean steady = mean >= 300 && mean <= 20_000;
			for (int i = 1; i < r.n && steady; i++) {
				if (Math.abs((r.t[i] - r.t[i - 1]) - mean) > 0.2 * mean) {
					steady = false;
				}
			}
			if (steady) {
				a.farmSeen = nowMs;
				a.period = mean;
			}
		}
		if (rhythms.size() > 8192) {
			rhythms.keySet().removeIf(k -> nowMs - rhythms.get(k).t[rhythms.get(k).n - 1] > 120_000);
		}
		return true;
	}

	public synchronized List<Cell> top(long nowMs, double minScore, int max) {
		return top(nowMs, minScore, max, HALF_LIFE_MS);
	}

	/** The same top list, decayed over the module's Memory window. */
	public synchronized List<Cell> top(long nowMs, double minScore, int max, double halfLifeMs) {
		List<Cell> out = new ArrayList<>();
		for (Map.Entry<Long, Acc> e : cells.entrySet()) {
			Acc a = e.getValue();
			double f = decay(nowMs, a.last, halfLifeMs), score = a.score * f;
			if (score < minScore) {
				continue;
			}
			int best = 0;
			for (int i = 1; i < a.byKind.length; i++) {
				if (a.byKind[i] > a.byKind[best]) {
					best = i;
				}
			}
			long k = e.getKey();
			out.add(new Cell(Chunks.blockX(k), Chunks.blockY(k), Chunks.blockZ(k), score, SoundKinds.Kind.values()[best],
					nowMs - a.farmSeen < 60_000, a.period, a.lx, a.ly, a.lz));
		}
		out.sort(Comparator.comparingDouble(Cell::score).reversed());
		return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
	}

	public synchronized void clear() {
		cells.clear();
		rhythms.clear();
		lastSeen.clear();
	}
}
