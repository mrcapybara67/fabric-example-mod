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

	@Test
	void syntheticWorldLeavesOnlyTheCentreGap() {
		SusChunkEngine engine = new SusChunkEngine(4);
		for (int x = -12; x <= 12; x++) {
			for (int z = -12; z <= 12; z++) {
				boolean centre = Math.abs(x) <= 4 && Math.abs(z) <= 4;
				engine.report(Chunks.key(x, z), centre ? 0 : 50);
			}
		}

		Set<Long> flagged = engine.flagged(key -> true, SusChunkEngine.thresholdFor(4));

		assertEquals(Set.of(Chunks.key(0, 0)), flagged);
		assertTrue(engine.supportAt(Chunks.key(0, 0)) >= SusChunkEngine.thresholdFor(4));
		assertFalse(engine.isCleared(Chunks.key(0, 0)));
	}

	@Test
	void noEvidenceFlagsNothing() {
		SusChunkEngine engine = new SusChunkEngine(4);
		assertTrue(engine.flagged(key -> true, SusChunkEngine.thresholdFor(4)).isEmpty());
	}

	@Test
	void aStalledChunkFarFromEveryoneFlagsNothing() {
		SusChunkEngine engine = new SusChunkEngine(4);
		engine.report(Chunks.key(50, 50), 50);
		assertTrue(engine.flagged(key -> true, SusChunkEngine.thresholdFor(4)).isEmpty());
	}

	@Test
	void forgettingEveryReportedChunkLeavesTheEngineEmpty() {
		SusChunkEngine engine = new SusChunkEngine(4);
		long first = Chunks.key(0, 0);
		long second = Chunks.key(5, 0);
		engine.report(first, 40);
		engine.report(second, 50);
		assertTrue(engine.isCleared(Chunks.key(2, 0)));

		engine.forget(first);
		engine.forget(second);

		assertEquals(0, engine.scannedCount());
		assertEquals(0, engine.stalledChunks());
		assertEquals(0, engine.supportAt(first));
		assertFalse(engine.isCleared(Chunks.key(2, 0)));
		assertTrue(engine.flagged(key -> true, 1).isEmpty());
	}
}
