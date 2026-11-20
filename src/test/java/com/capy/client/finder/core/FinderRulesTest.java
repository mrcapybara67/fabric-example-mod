package com.capy.client.finder.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The pure rules the finder settings are wired through. */
class FinderRulesTest {

	@Test
	void plateFollowsThePlayerWhenAsked() {
		assertEquals(70.05, FinderRules.plateY(50.0, 70.0, true, 63), 1.0e-9);
	}

	@Test
	void plateHugsTerrainAndRespectsTheHeightCeiling() {
		assertEquals(50.05, FinderRules.plateY(50.0, 70.0, false, 63), 1.0e-9);
		assertEquals(63.05, FinderRules.plateY(80.0, 70.0, false, 63), 1.0e-9,
				"terrain above the ceiling is clipped to it");
		assertEquals(63.05, FinderRules.plateY(Double.NaN, 70.0, false, 63), 1.0e-9);
	}

	@Test
	void verticalRunNeedsTheFullLength() {
		assertTrue(FinderRules.hasVerticalRun(new boolean[]{true, true, true}, 3));
		assertFalse(FinderRules.hasVerticalRun(new boolean[]{true, true, false, true}, 3));
		assertFalse(FinderRules.hasVerticalRun(new boolean[0], 3));
		assertFalse(FinderRules.hasVerticalRun(null, 3));
	}

	@Test
	void memoryWindowBecomesAHalfLife() {
		assertEquals(30_000.0, FinderRules.halfLifeForWindowSeconds(60), 1.0e-9);
		assertEquals(5_000.0, FinderRules.halfLifeForWindowSeconds(10), 1.0e-9);
	}

	@Test
	void scoreFadeIsZeroAtThresholdAndFullAtTwiceIt() {
		assertEquals(0.0, FinderRules.scoreFade(12.0, 12.0), 1.0e-9);
		assertEquals(0.5, FinderRules.scoreFade(18.0, 12.0), 1.0e-9);
		assertEquals(1.0, FinderRules.scoreFade(30.0, 12.0), 1.0e-9);
	}

	@Test
	void ignoreRadiusUsesBlockDistanceFromTheChunkCentre() {
		assertTrue(FinderRules.withinBlocks(0, 0, 8.0, 8.0, 16));
		assertFalse(FinderRules.withinBlocks(0, 0, 100.0, 100.0, 16));
		assertFalse(FinderRules.withinBlocks(0, 0, 8.0, 8.0, 0), "zero radius disables the filter");
	}

	@Test
	void approachingRequiresMovementCloserToTheObserver() {
		assertTrue(FinderRules.approaching(20.0, 10.0, 1.0));
		assertFalse(FinderRules.approaching(10.0, 20.0, 1.0));
		assertFalse(FinderRules.approaching(20.0, 10.0, 0.1), "standing still is not approaching");
	}

	@Test
	void colourRampHitsBothEnds() {
		int low = 0xFF000000;
		int high = 0xFFFF8040;
		assertEquals(low, FinderRules.ramp(low, high, 0.0));
		assertEquals(high, FinderRules.ramp(low, high, 1.0));
		int middle = FinderRules.ramp(low, high, 0.5);
		assertEquals(0xFF, middle >>> 24);
		assertTrue(((middle >>> 16) & 0xFF) > 0x70);
	}
}
