package com.capy.client.antivanish.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProbePlannerTest {
	/** Makes every gap the minimum, so the 25-second boundary is exact. */
	private static Random noJitter() {
		return new Random() {
			@Override
			public int nextInt(int bound) {
				return 0;
			}
		};
	}

	@Test
	void firstProbeGoesOutAndNoneGoesInside25Seconds() {
		ProbePlanner planner = new ProbePlanner(noJitter());
		planner.setNames(List.of("Alpha", "Bravo"));
		assertEquals("Alpha", planner.next(0, Set.of()));
		assertNull(planner.next(24_999, Set.of()));
		assertEquals("Bravo", planner.next(25_000, Set.of()));
	}

	@Test
	void rotatesThroughNames() {
		ProbePlanner planner = new ProbePlanner(noJitter());
		planner.setNames(List.of("Alpha", "Bravo", "Charlie"));
		assertEquals("Alpha", planner.next(0, Set.of()));
		assertEquals("Bravo", planner.next(25_000, Set.of()));
		assertEquals("Charlie", planner.next(50_000, Set.of()));
		assertEquals("Alpha", planner.next(75_000, Set.of()));
	}

	@Test
	void skipsNamesCurrentlyInTheTabList() {
		ProbePlanner planner = new ProbePlanner(noJitter());
		planner.setNames(List.of("Alpha", "Bravo"));
		assertEquals("Bravo", planner.next(0, Set.of("alpha")));
		assertEquals("Alpha", planner.next(25_000, Set.of("bravo")));
	}

	@Test
	void atMostSixPerTenMinutes() {
		ProbePlanner planner = new ProbePlanner(noJitter());
		planner.setNames(List.of("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf"));
		long now = 0;
		for (int i = 0; i < 6; i++) {
			assertNotNull(planner.next(now, Set.of()), "probe " + i);
			now += 25_000;
		}
		assertNull(planner.next(now, Set.of()));
	}

	@Test
	void rateLimitDoublesTheWaitAndNeverShortensIt() {
		ProbePlanner planner = new ProbePlanner(noJitter());
		planner.setNames(List.of("Alpha", "Bravo"));
		assertEquals("Alpha", planner.next(0, Set.of()));
		planner.rateLimited(0);
		assertNull(planner.next(29_999, Set.of()));
		assertEquals("Bravo", planner.next(30_000, Set.of()));
		planner.rateLimited(30_000);
		assertNull(planner.next(89_999, Set.of()));
		assertNotNull(planner.next(90_000, Set.of()));
		planner.rateLimited(10_000);
		assertNull(planner.next(174_999, Set.of()));
		assertNotNull(planner.next(175_000, Set.of()));
	}
}
