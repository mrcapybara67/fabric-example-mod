package com.capy.client.module.modules.esp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chunk scores with a spread radius and local-maximum extraction.
 *
 * <p>Adding a score adds it to every chunk in the square radius; removing the
 * old score before a rescan is what keeps the map from accumulating forever.
 * The peak pass uses a separable sliding-window maximum while the dense grid is
 * at most one million cells, and falls back to a direct scan beyond that. The
 * dense grid is bounded by the loaded scan area, not by the world.</p>
 */
public final class HeatMap {
	private static final int MAX_DENSE_CELLS = 1_000_000;
	private static final int MAX_SCORES = 1_000_000;
	private final Map<Long, Double> scores = new LinkedHashMap<>();

	public void add(int chunkX, int chunkZ, double score, int radius) {
		if (!Double.isFinite(score) || score == 0.0) {
			return;
		}
		for (int dz = -radius; dz <= radius; dz++) {
			for (int dx = -radius; dx <= radius; dx++) {
				long key = key(chunkX + dx, chunkZ + dz);
				scores.merge(key, score, Double::sum);
			}
		}
		while (scores.size() > MAX_SCORES) {
			scores.remove(scores.keySet().iterator().next());
		}
	}

	public void remove(int chunkX, int chunkZ, double score, int radius) {
		if (!Double.isFinite(score) || score == 0.0) {
			return;
		}
		for (int dz = -radius; dz <= radius; dz++) {
			for (int dx = -radius; dx <= radius; dx++) {
				long key = key(chunkX + dx, chunkZ + dz);
				double next = scores.getOrDefault(key, 0.0) - score;
				if (next <= 1.0e-9) {
					scores.remove(key);
				} else {
					scores.put(key, next);
				}
			}
		}
	}

	/** Replaces one chunk's contribution, removing the previous spread first. */
	public void replace(int chunkX, int chunkZ, double oldScore, double newScore, int radius) {
		remove(chunkX, chunkZ, oldScore, radius);
		add(chunkX, chunkZ, newScore, radius);
	}

	public double score(int chunkX, int chunkZ) {
		return scores.getOrDefault(key(chunkX, chunkZ), 0.0);
	}

	public void clear() {
		scores.clear();
	}

	public int size() {
		return scores.size();
	}

	/** Chunks whose score equals the maximum in the square radius. */
	public List<Long> peaks(int radius) {
		List<Long> out = new ArrayList<>();
		if (scores.isEmpty()) {
			return out;
		}
		int minX = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (Long key : scores.keySet()) {
			int x = x(key);
			int z = z(key);
			minX = Math.min(minX, x);
			minZ = Math.min(minZ, z);
			maxX = Math.max(maxX, x);
			maxZ = Math.max(maxZ, z);
		}
		long width = (long) maxX - minX + 1;
		long height = (long) maxZ - minZ + 1;
		if (width <= 0 || height <= 0) {
			return out;
		}
		if (width * height > MAX_DENSE_CELLS) {
			for (Map.Entry<Long, Double> entry : scores.entrySet()) {
				if (isLocalPeak(entry.getKey(), entry.getValue(), radius)) {
					out.add(entry.getKey());
				}
			}
			return out;
		}
		int w = (int) width;
		int h = (int) height;
		double[][] grid = new double[h][w];
		for (int z = 0; z < h; z++) {
			java.util.Arrays.fill(grid[z], Double.NEGATIVE_INFINITY);
		}
		for (Map.Entry<Long, Double> entry : scores.entrySet()) {
			grid[z(entry.getKey()) - minZ][x(entry.getKey()) - minX] = entry.getValue();
		}
		double[][] horizontal = new double[h][w];
		for (int z = 0; z < h; z++) {
			slide(grid[z], horizontal[z], radius);
		}
		for (int x = 0; x < w; x++) {
			double[] column = new double[h];
			for (int z = 0; z < h; z++) {
				column[z] = horizontal[z][x];
			}
			double[] maxima = new double[h];
			slide(column, maxima, radius);
			for (int z = 0; z < h; z++) {
				double value = grid[z][x];
				if (value != Double.NEGATIVE_INFINITY && value > 0.0
						&& Math.abs(value - maxima[z]) < 1.0e-9) {
					out.add(key(minX + x, minZ + z));
				}
			}
		}
		return out;
	}

	private boolean isLocalPeak(long key, double value, int radius) {
		if (value <= 0.0) {
			return false;
		}
		int cx = x(key);
		int cz = z(key);
		for (int dz = -radius; dz <= radius; dz++) {
			for (int dx = -radius; dx <= radius; dx++) {
				if (scores.getOrDefault(key(cx + dx, cz + dz), 0.0) > value + 1.0e-9) {
					return false;
				}
			}
		}
		return true;
	}

	private static void slide(double[] input, double[] output, int radius) {
		int n = input.length;
		int window = radius * 2 + 1;
		java.util.ArrayDeque<Integer> deque = new java.util.ArrayDeque<>();
		int right = 0;
		for (int i = 0; i < n; i++) {
			int target = Math.min(n - 1, i + radius);
			while (right <= target) {
				while (!deque.isEmpty() && input[deque.peekLast()] <= input[right]) {
					deque.removeLast();
				}
				deque.addLast(right++);
			}
			while (!deque.isEmpty() && deque.peekFirst() < i - radius) {
				deque.removeFirst();
			}
			output[i] = deque.isEmpty() ? Double.NEGATIVE_INFINITY : input[deque.peekFirst()];
		}
	}

	public static long key(int x, int z) {
		return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
	}

	public static int x(long key) {
		return (int) (key >> 32);
	}

	public static int z(long key) {
		return (int) key;
	}
}
