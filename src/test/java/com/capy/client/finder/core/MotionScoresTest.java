package com.capy.client.finder.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MotionScoresTest {
	@Test
	void flagsABusyChunkAndLeavesQuietOnesAlone() {
		MotionScores scores = new MotionScores();
		long now = 10_000L;
		long busy = Chunks.key(0, 0);
		scores.add(busy, 100.0, now);
		for (int i = 1; i <= 40; i++) {
			scores.add(Chunks.key(i, 0), 0.1, now);
		}

		Map<Long, MotionScores.Level> flagged = scores.busy(now, 5.0);

		assertTrue(flagged.containsKey(busy));
		assertFalse(flagged.containsKey(Chunks.key(1, 0)));
	}

	@Test
	void scoreHalvesAfterThirtySeconds() {
		MotionScores scores = new MotionScores();
		long chunk = Chunks.key(12, -3);
		scores.add(chunk, 10.0, 1_000L);

		assertEquals(5.0, scores.score(chunk, 31_000L), 1.0e-9);
	}
}
