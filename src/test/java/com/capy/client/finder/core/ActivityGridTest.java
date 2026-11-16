package com.capy.client.finder.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ActivityGridTest {
	private static final SoundKinds.Kind MINING = SoundKinds.Kind.MINING;

	@Test
	void steadyRhythmIsAFarm() {
		ActivityGrid grid = new ActivityGrid();
		for (int i = 0; i < 4; i++) {
			assertTrue(grid.add(MINING, 1, 8.5, 64.5, 8.5, i * 3_000L));
		}
		List<ActivityGrid.Cell> cells = grid.top(9_000, 0.1, 10);
		assertEquals(1, cells.size());
		ActivityGrid.Cell cell = cells.getFirst();
		assertTrue(cell.farm());
		assertEquals(3_000, cell.periodMs(), 1e-9);
		double f = Math.pow(0.5, 3_000 / ActivityGrid.HALF_LIFE_MS);
		assertEquals(1 + f + f * f + f * f * f, cell.score(), 1e-9);
		assertEquals(0, cell.sx());
		assertEquals(4, cell.sy());
		assertEquals(0, cell.sz());
	}

	@Test
	void irregularEventsAreNotAFarm() {
		ActivityGrid grid = new ActivityGrid();
		long[] times = {0, 1_000, 5_000, 9_000};
		for (long now : times) {
			assertTrue(grid.add(MINING, 1, 8, 64, 8, now));
		}
		List<ActivityGrid.Cell> cells = grid.top(9_000, 0.1, 10);
		assertEquals(1, cells.size());
		assertFalse(cells.getFirst().farm());
	}

	@Test
	void duplicateWithin300msIsIgnored() {
		ActivityGrid grid = new ActivityGrid();
		assertTrue(grid.add(MINING, 1, 0.5, 0.5, 0.5, 1_000));
		assertFalse(grid.add(MINING, 1, 0.5, 0.5, 0.5, 1_299));
		assertTrue(grid.add(MINING, 1, 0.5, 0.5, 0.5, 1_300));
		double score = grid.top(1_300, 0, 10).getFirst().score();
		assertTrue(score > 1.9 && score < 2.0, "score " + score);
	}

	@Test
	void scoreHalvesAfterThirtySeconds() {
		ActivityGrid grid = new ActivityGrid();
		assertTrue(grid.add(MINING, 1, 0, 0, 0, 0));
		assertEquals(1.0, grid.top(0, 0, 10).getFirst().score(), 1e-9);
		assertEquals(0.5, grid.top(30_000, 0, 10).getFirst().score(), 1e-9);
		assertEquals(0.25, grid.top(60_000, 0, 10).getFirst().score(), 1e-9);
	}

	@Test
	void cellsAreRankedByScoreAndLimited() {
		ActivityGrid grid = new ActivityGrid();
		assertTrue(grid.add(MINING, 3, 0.5, 0.5, 0.5, 0));
		assertTrue(grid.add(SoundKinds.Kind.REDSTONE, 1, 100.5, 0.5, 0.5, 0));
		List<ActivityGrid.Cell> cells = grid.top(0, 0, 10);
		assertEquals(2, cells.size());
		assertEquals(3.0, cells.get(0).score(), 1e-9);
		assertEquals(SoundKinds.Kind.MINING, cells.get(0).dominant());
		assertEquals(1.0, cells.get(1).score(), 1e-9);

		assertEquals(1, grid.top(0, 0, 1).size());
		assertTrue(grid.top(0, 5, 10).isEmpty());
	}

	@Test
	void dominantKindFollowsAccumulatedWeight() {
		ActivityGrid grid = new ActivityGrid();
		assertTrue(grid.add(MINING, 1, 0.5, 0.5, 0.5, 0));
		assertTrue(grid.add(SoundKinds.Kind.REDSTONE, 5, 0.5, 0.5, 0.5, 1_000));
		assertEquals(SoundKinds.Kind.REDSTONE, grid.top(1_000, 0, 10).getFirst().dominant());
	}

	@Test
	void lastEventPositionIsKept() {
		ActivityGrid grid = new ActivityGrid();
		assertTrue(grid.add(MINING, 1, 1.5, 2.25, 3.75, 1_000));
		ActivityGrid.Cell cell = grid.top(1_000, 0, 10).getFirst();
		assertEquals(1.5, cell.x(), 0);
		assertEquals(2.25, cell.y(), 0);
		assertEquals(3.75, cell.z(), 0);
	}

	@Test
	void ignoredKindsAndZeroWeightsAreNotStored() {
		ActivityGrid grid = new ActivityGrid();
		assertFalse(grid.add(SoundKinds.Kind.IGNORE, 5, 0, 0, 0, 1_000));
		assertFalse(grid.add(MINING, 0, 0, 0, 0, 1_000));
		assertTrue(grid.top(1_000, 0, 10).isEmpty());
		grid.clear();
		assertTrue(grid.top(1_000, 0, 10).isEmpty());
	}
}
