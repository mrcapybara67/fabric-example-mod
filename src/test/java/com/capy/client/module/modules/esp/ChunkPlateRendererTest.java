package com.capy.client.module.modules.esp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ChunkPlateRendererTest {
	@Test
	void adjacentChunksBecomeOneMaximalRectangle() {
		List<ChunkPlateRenderer.Rect> rects = ChunkPlateRenderer.merge(Set.of(
				HeatMap.key(0, 0), HeatMap.key(1, 0), HeatMap.key(0, 1), HeatMap.key(1, 1)));
		assertEquals(1, rects.size());
		assertEquals(2, rects.get(0).width());
		assertEquals(2, rects.get(0).depth());
	}

	@Test
	void separatedRunsProduceSeparateRectangles() {
		List<ChunkPlateRenderer.Rect> rects = ChunkPlateRenderer.merge(Set.of(
				HeatMap.key(0, 0), HeatMap.key(1, 0), HeatMap.key(4, 0)));
		assertEquals(2, rects.size());
		assertTrue(rects.stream().anyMatch(r -> r.minX() == 0 && r.maxX() == 1));
		assertTrue(rects.stream().anyMatch(r -> r.minX() == 4 && r.maxX() == 4));
	}

	@Test
	void emptySetHasNoPlate() {
		assertTrue(ChunkPlateRenderer.merge(Set.of()).isEmpty());
	}
}
