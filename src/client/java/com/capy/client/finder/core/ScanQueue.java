package com.capy.client.finder.core;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;

/** Pending chunk scans: no duplicates, nearest chunk to the player first. Thread-safe. */
public final class ScanQueue {
	private final LinkedHashSet<Long> pending = new LinkedHashSet<>();

	public synchronized boolean offer(long key) {
		return pending.add(key);
	}

	public synchronized void offerAll(Collection<Long> keys) {
		pending.addAll(keys);
	}

	public synchronized void remove(long key) {
		pending.remove(key);
	}

	public synchronized void clear() {
		pending.clear();
	}

	public synchronized int size() {
		return pending.size();
	}

	public synchronized boolean contains(long key) {
		return pending.contains(key);
	}

	public synchronized Long pollNearest(int cx, int cz) {
		Long best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (Iterator<Long> it = pending.iterator(); it.hasNext(); ) {
			long key = it.next();
			int d = Math.max(Math.abs(Chunks.x(key) - cx), Math.abs(Chunks.z(key) - cz));
			if (d < bestDistance) {
				bestDistance = d;
				best = key;
			}
		}
		if (best != null) {
			pending.remove(best);
		}
		return best;
	}
}
