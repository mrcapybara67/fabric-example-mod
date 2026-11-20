package com.capy.client.finder.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Decides which flags share one beam.
 *
 * <p>A base made of a dozen tunnel holes would otherwise grow a dozen beams
 * side by side, which reads as a fence rather than as one place. The greedy
 * pass keeps the first flag of a cluster and drops every flag within
 * {@code radius} chunks of a kept one, so the beam count follows the number of
 * separate areas instead of the number of holes.</p>
 */
public final class FinderBeams {
	private FinderBeams() {
	}

	/** One representative chunk key per cluster, in a stable order. */
	public static List<Long> coalesce(Collection<Long> keys, int radius) {
		List<Long> ordered = new ArrayList<>(keys);
		ordered.sort(Comparator.comparingInt(Chunks::x).thenComparingInt(Chunks::z));
		int spread = Math.max(0, radius);
		List<Long> kept = new ArrayList<>();
		for (long key : ordered) {
			if (!nearAny(kept, key, spread)) {
				kept.add(key);
			}
		}
		return kept;
	}

	private static boolean nearAny(List<Long> kept, long key, int radius) {
		int x = Chunks.x(key);
		int z = Chunks.z(key);
		for (long other : kept) {
			if (Math.abs(Chunks.x(other) - x) <= radius && Math.abs(Chunks.z(other) - z) <= radius) {
				return true;
			}
		}
		return false;
	}
}
