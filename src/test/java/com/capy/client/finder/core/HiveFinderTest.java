package com.capy.client.finder.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class HiveFinderTest {
	@Test
	void aChunkMustSettleBeforeItCanBeAFlag() {
		HiveFinder finder = new HiveFinder();
		assertFalse(finder.settled(Chunks.key(0, 0), 100));
		assertFalse(finder.settled(Chunks.key(0, 0), 299));
		assertTrue(finder.settled(Chunks.key(0, 0), 300));
	}

	@Test
	void nearbyHitsMergeIntoTheirMiddleChunk() {
		Set<Long> merged = HiveFinder.merge(Set.of(Chunks.key(0, 0), Chunks.key(3, 3)), 4);
		assertEquals(Set.of(Chunks.key(2, 2)), merged);
	}

	@Test
	void distantHitsStaySeparate() {
		Set<Long> merged = HiveFinder.merge(Set.of(Chunks.key(0, 0), Chunks.key(20, 20)), 4);
		assertEquals(2, merged.size());
	}

	@Test
	void retainRemovesUnloadedHits() {
		HiveFinder finder = new HiveFinder();
		finder.setHit(Chunks.key(0, 0), true);
		finder.setHit(Chunks.key(2, 2), true);
		assertTrue(finder.retain(key -> key == Chunks.key(0, 0)));
		assertEquals(Set.of(Chunks.key(0, 0)), finder.hits());
	}
}
