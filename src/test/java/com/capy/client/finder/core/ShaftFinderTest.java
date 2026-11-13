package com.capy.client.finder.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ShaftFinderTest {
	@Test
	void oneByOneShaftNeedsFourClosedSides() {
		ShaftFinder.OpenMap map = closedMap(0, 12);
		for (int y = 2; y <= 8; y++) {
			map.set(8, y, 8, true);
		}
		List<ShaftFinder.Shaft> shafts = ShaftFinder.find(map, 0, 0, 4, true, false);
		assertEquals(1, shafts.size());
		ShaftFinder.Shaft shaft = shafts.get(0);
		assertEquals(ShaftFinder.Shape.ONE, shaft.shape());
		assertEquals(2, shaft.minY());
		assertEquals(9, shaft.maxY());
		assertEquals(7, shaft.height());
	}

	@Test
	void threeByOneShaftNeedsBothLongSidesAndEnds() {
		ShaftFinder.OpenMap map = closedMap(0, 12);
		for (int y = 1; y <= 7; y++) {
			for (int x = 5; x <= 7; x++) {
				map.set(x, y, 8, true);
			}
		}
		List<ShaftFinder.Shaft> shafts = ShaftFinder.find(map, 0, 0, 4, false, true);
		assertTrue(shafts.stream().anyMatch(s -> s.shape() == ShaftFinder.Shape.THREE_X && s.x() == 5));
		assertEquals(1, shafts.stream().filter(s -> s.shape() == ShaftFinder.Shape.THREE_X).count());
	}

	@Test
	void anOpenEndDoesNotMatch() {
		ShaftFinder.OpenMap map = closedMap(0, 12);
		for (int y = 1; y <= 7; y++) {
			for (int x = 5; x <= 8; x++) {
				map.set(x, y, 8, true);
			}
		}
		List<ShaftFinder.Shaft> shafts = ShaftFinder.find(map, 0, 0, 4, false, true);
		assertFalse(shafts.stream().anyMatch(s -> s.shape() == ShaftFinder.Shape.THREE_X));
	}

	private static ShaftFinder.OpenMap closedMap(int minY, int height) {
		ShaftFinder.OpenMap map = new ShaftFinder.OpenMap(minY, height);
		for (int y = minY; y < minY + height; y++) {
			for (int x = -ShaftFinder.OpenMap.PAD; x < 16 + ShaftFinder.OpenMap.PAD; x++) {
				for (int z = -ShaftFinder.OpenMap.PAD; z < 16 + ShaftFinder.OpenMap.PAD; z++) {
					map.set(x, y, z, false);
				}
			}
		}
		return map;
	}
}
