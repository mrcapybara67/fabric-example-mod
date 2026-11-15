package com.capy.client.finder.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ChunkReloadTest {
	@Test
	void appliesAtYMinusOneRestoresAfterTenTicksAndRearmsAboveIt() {
		ChunkReload state = new ChunkReload();

		assertEquals(ChunkReload.Action.NONE, state.tick(10, 12));
		assertEquals(ChunkReload.Action.APPLY, state.tick(-1, 12));
		assertEquals(12, state.savedDistance());

		for (int i = 0; i < ChunkReload.RESTORE_DELAY_TICKS - 1; i++) {
			assertEquals(ChunkReload.Action.NONE, state.tick(-1, 12));
		}
		assertEquals(ChunkReload.Action.RESTORE,
				state.tick(-1, ChunkReload.RESTORE_DELAY_TICKS));

		// Restored but still under the re-arm level: no new cycle.
		assertEquals(ChunkReload.Action.NONE, state.tick(0, 12));
		assertEquals(ChunkReload.Action.NONE, state.tick(0, 12));

		// Y 1 is two blocks above the level that triggered it.
		assertEquals(ChunkReload.Action.NONE, state.tick(1, 12));
		assertEquals(ChunkReload.Action.APPLY, state.tick(-1, 12));
	}

	@Test
	void resetForgetsTheSavedDistance() {
		ChunkReload state = new ChunkReload();
		assertEquals(ChunkReload.Action.APPLY, state.tick(-4, 16));
		state.reset();

		assertEquals(-1, state.savedDistance());
		assertEquals(ChunkReload.Action.NONE, state.tick(10, 16));
	}
}
