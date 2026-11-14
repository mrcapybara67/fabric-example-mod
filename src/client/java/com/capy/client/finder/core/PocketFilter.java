package com.capy.client.finder.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Rejects the "no plants here" case before it becomes a huge red patch.
 *
 * <p>A single player's random-tick footprint makes one small pocket. A large
 * connected area that still reports stalled growth is much more likely to be a
 * naturally plantless region than a group of players, so patches above the
 * simulation-distance budget are dropped rather than drawn as a base.</p>
 */
public final class PocketFilter {
	private PocketFilter() {
	}

	public record Patch(long center, int size, Set<Long> members) {
	}

	public static int maxSizeFor(int simulationDistance) {
		return Math.max(4, (simulationDistance + 1) * (simulationDistance + 1));
	}

	public static List<Patch> patches(Set<Long> flagged, int maxSize) {
		List<Patch> out = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		for (long start : flagged) {
			if (!seen.add(start)) {
				continue;
			}
			Set<Long> members = new HashSet<>();
			ArrayDeque<Long> queue = new ArrayDeque<>();
			queue.add(start);
			double sx = 0;
			double sz = 0;
			while (!queue.isEmpty()) {
				long k = queue.poll();
				members.add(k);
				sx += Chunks.x(k);
				sz += Chunks.z(k);
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						long n = Chunks.key(Chunks.x(k) + dx, Chunks.z(k) + dz);
						if (flagged.contains(n) && seen.add(n)) {
							queue.add(n);
						}
					}
				}
			}
			if (members.size() > maxSize) {
				continue;
			}
			double mx = sx / members.size();
			double mz = sz / members.size();
			long best = start;
			double bestD = Double.MAX_VALUE;
			for (long k : members) {
				double d = Math.pow(Chunks.x(k) - mx, 2) + Math.pow(Chunks.z(k) - mz, 2);
				if (d < bestD) {
					bestD = d;
					best = k;
				}
			}
			out.add(new Patch(best, members.size(), members));
		}
		return out;
	}
}
