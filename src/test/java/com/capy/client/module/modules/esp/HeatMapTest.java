package com.capy.client.module.modules.esp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class HeatMapTest {
	@Test
	void spreadAddsAndUndoRemovesTheContribution() {
		HeatMap map = new HeatMap();
		map.add(10, 10, 3, 1);
		assertEquals(3, map.score(9, 10), 1.0e-9);
		assertEquals(3, map.score(11, 11), 1.0e-9);
		map.remove(10, 10, 3, 1);
		assertEquals(0, map.score(10, 10), 1.0e-9);
	}

	@Test
	void localMaximaAreReturned() {
		HeatMap map = new HeatMap();
		map.add(0, 0, 2, 0);
		map.add(1, 0, 5, 0);
		List<Long> peaks = map.peaks(1);
		assertTrue(peaks.contains(HeatMap.key(1, 0)));
		assertTrue(!peaks.contains(HeatMap.key(0, 0)));
	}

	@Test
	void replaceDoesNotAccumulate() {
		HeatMap map = new HeatMap();
		map.replace(2, 2, 0, 4, 0);
		map.replace(2, 2, 4, 1, 0);
		assertEquals(1, map.score(2, 2), 1.0e-9);
	}
}
