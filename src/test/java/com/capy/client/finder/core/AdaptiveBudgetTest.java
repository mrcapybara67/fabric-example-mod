package com.capy.client.finder.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AdaptiveBudgetTest {
	@Test
	void budgetFollowsFrameRate() {
		assertEquals(2_000_000L, AdaptiveBudget.nanosForFps(144));
		assertEquals(2_000_000L, AdaptiveBudget.nanosForFps(60));
		assertEquals(1_000_000L, AdaptiveBudget.nanosForFps(59));
		assertEquals(1_000_000L, AdaptiveBudget.nanosForFps(30));
		assertEquals(500_000L, AdaptiveBudget.nanosForFps(29));
		assertEquals(500_000L, AdaptiveBudget.nanosForFps(1));
	}
}
