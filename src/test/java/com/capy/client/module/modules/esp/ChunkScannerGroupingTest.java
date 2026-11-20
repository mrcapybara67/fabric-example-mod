package com.capy.client.module.modules.esp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.capy.client.finder.core.Chunks;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Spread Radius groups nearby marked chunks and keeps the strongest. */
class ChunkScannerGroupingTest {

	@Test
	void spreadOneMergesNeighboursAndKeepsTheTallest() {
		Map<Long, Integer> heights = new HashMap<>();
		heights.put(Chunks.key(10, 10), 5);
		heights.put(Chunks.key(11, 10), 3);
		heights.put(Chunks.key(10, 11), 4);
		Map<Long, Integer> winners = ChunkScannerModule.keepTallest(heights, 1);
		assertEquals(1, winners.size());
		assertEquals(5, winners.get(Chunks.key(10, 10)));
	}

	@Test
	void spreadZeroKeepsOnePerChunk() {
		Map<Long, Integer> heights = new HashMap<>();
		heights.put(Chunks.key(10, 10), 5);
		heights.put(Chunks.key(11, 10), 3);
		assertEquals(2, ChunkScannerModule.keepTallest(heights, 0).size());
	}

	@Test
	void aWideSpreadMergesADistantPairThatANarrowOneDoesNot() {
		Map<Long, Integer> heights = new HashMap<>();
		heights.put(Chunks.key(0, 0), 4);
		heights.put(Chunks.key(5, 5), 6);
		assertEquals(2, ChunkScannerModule.keepTallest(heights, 2).size());
		assertEquals(1, ChunkScannerModule.keepTallest(heights, 8).size());
	}
}
