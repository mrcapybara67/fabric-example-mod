package com.capy.client.module.modules.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BoatSinkTest {
	@Test
	void idleBoatSinksAtTheConfiguredRate() {
		assertEquals(-0.5, BoatSink.target(0, 0, 0, 0.5), 1.0e-9);
	}

	@Test
	void anyHorizontalInputRemovesTheAutomaticSink() {
		assertEquals(0.0, BoatSink.target(1, 0, 0, 0.5), 1.0e-9);
		assertEquals(0.0, BoatSink.target(0, -1, 0, 0.5), 1.0e-9);
		assertEquals(0.0, BoatSink.target(1, 1, 0, 0.5), 1.0e-9);
	}

	@Test
	void liftStillOverridesTheSink() {
		assertTrue(BoatSink.target(0, 0, 1, 0.5) > 0);
		assertTrue(BoatSink.target(0, 0, -1, 0.5) < 0);
	}
}
