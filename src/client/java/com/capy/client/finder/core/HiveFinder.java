package com.capy.client.finder.core;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;

/** Full-beehive finder bookkeeping: settle time, hit set, and merging nearby hits into one middle flag. */
public final class HiveFinder {
	public static final int SETTLE_TICKS = 200;
	public static final int MERGE_RADIUS = 4;

	private final Map<Long, Integer> firstSeen = new HashMap<>();
	private final Set<Long> hits = new HashSet<>();

	public boolean settled(long key, int tick) {
		int first = firstSeen.computeIfAbsent(key, k -> tick);
		return tick - first >= SETTLE_TICKS;
	}

	/** Returns true when the hit set changed. */
	public boolean setHit(long key, boolean hit) {
		return hit ? hits.add(key) : hits.remove(key);
	}

	/** Forget chunks that fail the predicate. Returns true if a hit was removed. */
	public boolean retain(LongPredicate keep) {
		firstSeen.keySet().removeIf(k -> !keep.test(k));
		return hits.removeIf(k -> !keep.test(k));
	}

	public Set<Long> hits() {
		return hits;
	}

	public void clear() {
		firstSeen.clear();
		hits.clear();
	}

	/** Hits within radius chunks of each other become ONE chunk: the rounded average of the group. */
	public static Set<Long> merge(Set<Long> hits, int radius) {
		Set<Long> out = new HashSet<>();
		if (hits.size() < 2) {
			out.addAll(hits);
			return out;
		}
		int r = Math.max(1, radius);
		Set<Long> visited = new HashSet<>();
		for (long start : hits) {
			if (!visited.add(start)) {
				continue;
			}
			ArrayDeque<Long> queue = new ArrayDeque<>();
			queue.add(start);
			long sx = 0;
			long sz = 0;
			int n = 0;
			while (!queue.isEmpty()) {
				long key = queue.poll();
				int cx = Chunks.x(key);
				int cz = Chunks.z(key);
				sx += cx;
				sz += cz;
				n++;
				for (int dx = -r; dx <= r; dx++) {
					for (int dz = -r; dz <= r; dz++) {
						if (dx == 0 && dz == 0) {
							continue;
						}
						long next = Chunks.key(cx + dx, cz + dz);
						if (hits.contains(next) && visited.add(next)) {
							queue.add(next);
						}
					}
				}
			}
			out.add(n == 1 ? start : Chunks.key((int) Math.round((double) sx / n),
					(int) Math.round((double) sz / n)));
		}
		return out;
	}
}
