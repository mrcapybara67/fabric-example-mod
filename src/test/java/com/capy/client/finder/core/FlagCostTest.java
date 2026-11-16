package com.capy.client.finder.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FlagCostTest {
	@Test
	void simplificationNeedsTwoSecondsAboveThreeMilliseconds() {
		FlagCost cost = new FlagCost();
		frame(cost, 0, 4.0);
		frame(cost, 1_999, 4.0);
		assertFalse(cost.simplified());
		frame(cost, 2_000, 4.0);
		assertTrue(cost.simplified());
	}

	@Test
	void restorationNeedsFiveSecondsBelowOneAndAHalfMilliseconds() {
		FlagCost cost = new FlagCost();
		frame(cost, 0, 4.0);
		frame(cost, 2_000, 4.0);
		assertTrue(cost.simplified());

		frame(cost, 3_000, 1.0);
		frame(cost, 7_999, 1.0);
		assertTrue(cost.simplified());
		frame(cost, 8_000, 1.0);
		assertFalse(cost.simplified());
	}

	@Test
	void aCostBetweenTheThresholdsNeitherLowersNorRestores() {
		FlagCost cost = new FlagCost();
		frame(cost, 0, 4.0);
		frame(cost, 2_000, 4.0);
		assertTrue(cost.simplified());

		frame(cost, 3_000, 2.0);
		frame(cost, 9_000, 2.0);
		assertTrue(cost.simplified());
	}

	@Test
	void moduleAverageIsSmoothedAndSkippedWhenAbsent() {
		FlagCost cost = new FlagCost();
		cost.add("hive", 10_000_000L);
		cost.add("hive", 2_000_000L);
		assertEquals(8.4, cost.moduleMs("hive"), 1.0e-9);
		assertEquals(0.0, cost.moduleMs("missing"));
		cost.endFrame(0);
		assertEquals(12.0, cost.lastTotalMs(), 1.0e-9);
	}

	private static void frame(FlagCost cost, long nowMs, double milliseconds) {
		cost.add("finder", Math.round(milliseconds * 1_000_000.0));
		cost.endFrame(nowMs);
	}
}
