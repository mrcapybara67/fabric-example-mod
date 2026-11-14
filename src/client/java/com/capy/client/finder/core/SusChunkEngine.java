package com.capy.client.finder.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;

/**
 * Sus Chunk by elimination. A chunk with "owed growth" (plants that should have grown but have not) is not being
 * random-ticked, so no player is within the simulation distance R of it. Every chunk within R of such a chunk is
 * eliminated. A chunk is flagged when it is NOT eliminated, has enough support (nearby stalled evidence), and at least
 * 3 of its 4 direction strips contain an eliminated chunk. The strips are 2*reach+1 wide, so that last test is loose;
 * the support threshold does most of the filtering.
 */
public final class SusChunkEngine {
	public static final int DEFAULT_THRESHOLD = 40;

	private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

	private record Record(boolean clears, int weight) {
	}

	private final int radius;
	private final Map<Long, Record> records = new HashMap<>();
	private final Map<Long, Integer> coverage = new HashMap<>();
	private final Map<Long, Integer> support = new HashMap<>();
	private final Set<Long> scanned = new HashSet<>();

	public SusChunkEngine(int simulationDistance) {
		this.radius = Math.max(1, simulationDistance);
	}

	public int radius() {
		return radius;
	}

	public int reach() {
		return 2 * radius + 2;
	}

	public static int thresholdFor(int simulationDistance) {
		return Math.max(10, DEFAULT_THRESHOLD * simulationDistance / 8);
	}

	/** weight = number of owed-growth blocks in the chunk. A chunk once seen stalled stays stalled. */
	public synchronized void report(long key, int weight) {
		Record old = records.get(key);
		if (old != null) {
			apply(key, old, -1);
		}
		Record merged = new Record(weight > 0 || (old != null && old.clears()),
				Math.max(weight, old == null ? 0 : old.weight()));
		records.put(key, merged);
		scanned.add(key);
		apply(key, merged, 1);
	}

	public synchronized int weight(long key) {
		Record record = records.get(key);
		return record == null ? 0 : record.weight();
	}

	public synchronized int supportAt(long key) {
		return support.getOrDefault(key, 0);
	}

	public synchronized boolean isCleared(long key) {
		return coverage.containsKey(key);
	}

	public synchronized int stalledChunks() {
		int n = 0;
		for (Record r : records.values()) {
			if (r.clears()) {
				n++;
			}
		}
		return n;
	}

	/** Chunks inside the elimination radius of at least one stalled record. */
	public synchronized Set<Long> clearedChunks() {
		return Set.copyOf(coverage.keySet());
	}

	public synchronized void forget(long key) {
		Record old = records.remove(key);
		scanned.remove(key);
		if (old != null) {
			apply(key, old, -1);
		}
	}

	/** Drop every record farther than keep chunks (Chebyshev) from the player chunk. */
	public synchronized void prune(int playerCx, int playerCz, int keep) {
		for (Long key : new HashSet<>(records.keySet())) {
			if (Math.abs(Chunks.x(key) - playerCx) > keep || Math.abs(Chunks.z(key) - playerCz) > keep) {
				forget(key);
			}
		}
	}

	public synchronized void clear() {
		records.clear();
		coverage.clear();
		support.clear();
		scanned.clear();
	}

	public synchronized int scannedCount() {
		return scanned.size();
	}

	public synchronized Set<Long> flagged(LongPredicate loaded, int threshold) {
		Set<Long> out = new HashSet<>();
		int reach = reach();
		for (long key : scanned) {
			if (!loaded.test(key) || coverage.containsKey(key)) {
				continue;
			}
			if (support.getOrDefault(key, 0) < threshold) {
				continue;
			}
			int cx = Chunks.x(key);
			int cz = Chunks.z(key);
			int sides = 0;
			for (int[] d : SIDES) {
				if (stripCovered(cx, cz, d[0], d[1], reach)) {
					sides++;
				}
			}
			if (sides >= 3) {
				out.add(key);
			}
		}
		return out;
	}

	private boolean stripCovered(int cx, int cz, int dx, int dz, int reach) {
		for (int depth = 1; depth <= reach; depth++) {
			for (int lateral = -reach; lateral <= reach; lateral++) {
				if (coverage.containsKey(Chunks.key(cx + dx * depth + dz * lateral,
						cz + dz * depth + dx * lateral))) {
					return true;
				}
			}
		}
		return false;
	}

	private void apply(long key, Record r, int sign) {
		if (!r.clears()) {
			return;
		}
		int cx = Chunks.x(key);
		int cz = Chunks.z(key);
		area(cx, cz, radius, coverage, sign);
		area(cx, cz, reach(), support, sign * r.weight());
	}

	private static void area(int cx, int cz, int r, Map<Long, Integer> map, int amount) {
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				long k = Chunks.key(cx + dx, cz + dz);
				int n = map.getOrDefault(k, 0) + amount;
				if (n <= 0) {
					map.remove(k);
				} else {
					map.put(k, n);
				}
			}
		}
	}
}
