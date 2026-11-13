package com.capy.client.finder.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class SusChunkEngineTest {
	@Test
	void aGapSurroundedOnThreeSidesIsFlagged() {
		SusChunkEngine engine = new SusChunkEngine(1);
		engine.report(Chunks.key(0, 0), 0);
		engine.report(Chunks.key(0, 3), 40);
		engine.report(Chunks.key(3, 0), 40);
		engine.report(Chunks.key(-3, 0), 40);

		Set<Long> flagged = engine.flagged(key -> true, 1);
		assertTrue(flagged.contains(Chunks.key(0, 0)));
		assertEquals(4, engine.scannedCount());
	}

	@Test
	void aCoveredChunkIsNeverFlagged() {
		SusChunkEngine engine = new SusChunkEngine(2);
		engine.report(Chunks.key(0, 0), 100);
		assertFalse(engine.flagged(key -> true, 1).contains(Chunks.key(0, 0)));
	}

	@Test
	void supportScalesWithSimulationDistance() {
		assertEquals(40, SusChunkEngine.thresholdFor(8));
		assertEquals(10, SusChunkEngine.thresholdFor(1));
		assertEquals(80, SusChunkEngine.thresholdFor(16));
	}

	@Test
	void pruningDropsFarRecords() {
		SusChunkEngine engine = new SusChunkEngine(2);
		engine.report(Chunks.key(50, 50), 40);
		engine.prune(0, 0, 8);
		assertEquals(0, engine.scannedCount());
	}
}
