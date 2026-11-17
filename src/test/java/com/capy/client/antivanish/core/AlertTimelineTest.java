package com.capy.client.antivanish.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AlertTimelineTest {
	@Test
	void startsAtZeroFadeAndEightyFivePercentPop() {
		double[] frame = AlertTimeline.frame(0.0);
		assertNotNull(frame);
		assertEquals(0.0, frame[0], 1e-9);
		assertEquals(0.85, frame[1], 1e-9);
		assertEquals(0.5, frame[2], 1e-9);
	}

	@Test
	void reachesFullFadeAndPopAtTheEndOfTheEntrance() {
		double[] frame = AlertTimeline.frame(AlertTimeline.IN);
		assertNotNull(frame);
		assertEquals(1.0, frame[0], 1e-9);
		assertEquals(1.0, frame[1], 1e-9);
	}

	@Test
	void fadesOutOverTheLastSecondAndEndsExactlyAtTotal() {
		double[] halfwayOut = AlertTimeline.frame(AlertTimeline.TOTAL - AlertTimeline.OUT / 2.0);
		assertNotNull(halfwayOut);
		assertEquals(0.125, halfwayOut[0], 1e-9);
		double[] end = AlertTimeline.frame(AlertTimeline.TOTAL);
		assertNotNull(end);
		assertEquals(0.0, end[0], 1e-9);
	}

	@Test
	void returnsNullBeforeAndAfterTheTimeline() {
		assertNull(AlertTimeline.frame(-0.001));
		assertNull(AlertTimeline.frame(AlertTimeline.TOTAL + 0.001));
	}

	@Test
	void pulseAlwaysStaysInsideZeroToOne() {
		for (int i = 0; i < 470; i++) {
			double[] frame = AlertTimeline.frame(i / 100.0);
			assertNotNull(frame);
			assertTrue(frame[2] >= 0.0 && frame[2] <= 1.0);
		}
	}
}
