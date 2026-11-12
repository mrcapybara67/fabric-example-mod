package com.capy.client.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VirtualListTest {
	@Test
	void cullsItemsAboveAndBelowTheViewport() {
		assertFalse(VirtualList.intersects(-100, 40, 0, 300));
		assertTrue(VirtualList.intersects(-20, 40, 0, 300));
		assertFalse(VirtualList.intersects(301, 40, 0, 300));
	}

	@Test
	void findsTheFirstAndLastVisibleItems() {
		int[] heights = new int[100];
		for (int i = 0; i < heights.length; i++) {
			heights[i] = 30;
		}
		assertArrayEquals(new int[]{8, 18}, VirtualList.range(300, 300, heights, 4));
	}

	@Test
	void emptyOrCollapsedViewportsProduceNoItems() {
		assertArrayEquals(new int[]{0, 0}, VirtualList.range(0, 0, new int[]{10, 10}, 4));
		assertArrayEquals(new int[]{0, 0}, VirtualList.range(0, 100, new int[0], 4));
	}
}
