package com.capy.client.finder.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LightRegionsTest {
	@Test
	void separatesTwoLitAreasAndPutsTheBrightestFirst() {
		Map<Long, Integer> lit = new HashMap<>();
		for (int x = 0; x < 3; x++) {
			for (int y = 0; y < 3; y++) {
				for (int z = 0; z < 3; z++) {
					lit.put(Chunks.block(x, y, z), 12);
				}
			}
		}
		// A dim tail joins the first area but is below its core, so the box
		// must stay tight around the bright 3x3x3 block.
		lit.put(Chunks.block(3, 1, 1), 5);

		for (int x = 20; x < 22; x++) {
			for (int y = 0; y < 2; y++) {
				for (int z = 0; z < 2; z++) {
					lit.put(Chunks.block(x, y, z), 9);
				}
			}
		}

		List<LightRegions.Box> boxes = LightRegions.group(lit, 5, 2000);

		assertEquals(2, boxes.size());
		LightRegions.Box first = boxes.get(0);
		assertEquals(12, first.peak());
		assertEquals(0, first.minX());
		assertEquals(0, first.minY());
		assertEquals(0, first.minZ());
		assertEquals(3, first.maxX());
		assertEquals(3, first.maxY());
		assertEquals(3, first.maxZ());

		LightRegions.Box second = boxes.get(1);
		assertEquals(9, second.peak());
		assertEquals(20, second.minX());
		assertEquals(22, second.maxX());
	}

	@Test
	void respectsTheBoxLimit() {
		Map<Long, Integer> lit = new HashMap<>();
		for (int x = 0; x < 4; x++) {
			lit.put(Chunks.block(x * 10, 0, 0), 10 + x);
		}

		assertEquals(2, LightRegions.group(lit, 5, 2).size());
	}
}
