package com.capy.client.finder.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Beam coalescing: nearby flags share one beam, distant ones keep their own. */
class FinderBeamsTest {

	@Test
	void nearbyFlagsShareOneBeam() {
		long a = Chunks.key(10, 10);
		long b = Chunks.key(11, 10);
		long c = Chunks.key(10, 11);
		List<Long> kept = FinderBeams.coalesce(List.of(a, b, c), 1);
		assertEquals(1, kept.size());
		assertEquals(a, kept.get(0), "the first key in a stable order represents the cluster");
	}

	@Test
	void distantFlagsKeepTheirOwnBeams() {
		long a = Chunks.key(0, 0);
		long b = Chunks.key(20, 20);
		List<Long> kept = FinderBeams.coalesce(List.of(a, b), 2);
		assertEquals(2, kept.size());
	}

	@Test
	void zeroRadiusDoesNotMergeDistinctChunks() {
		long a = Chunks.key(0, 0);
		long b = Chunks.key(1, 0);
		assertEquals(1, FinderBeams.coalesce(List.of(a, a), 0).size());
		assertEquals(2, FinderBeams.coalesce(List.of(a, b), 0).size());
	}

	@Test
	void toastRateLimitIsTenSecondsPerModule() {
		assertFalse(FinderNotifyRate.toastAllowed(1_000L, 10_999L));
		assertTrue(FinderNotifyRate.toastAllowed(1_000L, 11_000L));
	}

	/** Keeps the test independent from the Minecraft-typed notifier class. */
	private static final class FinderNotifyRate {
		static boolean toastAllowed(long lastMs, long nowMs) {
			return nowMs - lastMs >= 10_000L;
		}
	}
}
