package com.capy.client.finder.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Turns lit cells (block key -> block light) into one box per lit area, around its brightest core. */
public final class LightRegions {
	private LightRegions() {
	}

	public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int peak) {
	}

	private static final int[][] N = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

	public static List<Box> group(Map<Long, Integer> lit, int minLight, int maxBoxes) {
		List<Box> boxes = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		for (Map.Entry<Long, Integer> e : lit.entrySet()) {
			if (e.getValue() < minLight || !seen.add(e.getKey())) continue;
			List<Long> cells = new ArrayList<>();
			ArrayDeque<Long> queue = new ArrayDeque<>();
			queue.add(e.getKey());
			int peak = 0;
			while (!queue.isEmpty()) {
				long k = queue.poll();
				cells.add(k);
				peak = Math.max(peak, lit.get(k));
				int x = Chunks.blockX(k), y = Chunks.blockY(k), z = Chunks.blockZ(k);
				for (int[] d : N) {
					long nk = Chunks.block(x + d[0], y + d[1], z + d[2]);
					Integer v = lit.get(nk);
					if (v != null && v >= minLight && seen.add(nk)) queue.add(nk);
				}
			}
			int core = Math.max(minLight, peak - 1);
			int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
			int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
			for (long k : cells) {
				if (lit.get(k) < core) continue;
				int x = Chunks.blockX(k), y = Chunks.blockY(k), z = Chunks.blockZ(k);
				x0 = Math.min(x0, x); y0 = Math.min(y0, y); z0 = Math.min(z0, z);
				x1 = Math.max(x1, x); y1 = Math.max(y1, y); z1 = Math.max(z1, z);
			}
			boxes.add(new Box(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1, peak));
		}
		boxes.sort(Comparator.comparingInt(Box::peak).reversed());
		return boxes.size() > maxBoxes ? new ArrayList<>(boxes.subList(0, maxBoxes)) : boxes;
	}
}
