package com.capy.client.module.modules.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MovementRampTest {
	@Test
	void inertiaProducesMonotonicVelocityWithoutAnInstantJump() {
		InertiaRamp ramp = new InertiaRamp();
		double previous = 0.0;
		for (int tick = 0; tick < 12; tick++) {
			ramp.step(8.0, 0.0, 0.0, 0.75, false);
			assertTrue(ramp.x() > previous, "each tick moves towards the target");
			assertTrue(ramp.x() < 8.0, "the target is not reached in one tick");
			previous = ramp.x();
		}
		assertTrue(previous > 7.0, "the ramp has substantially arrived after twelve ticks");
	}

	@Test
	void legacyModeKeepsTheInstantOption() {
		InertiaRamp ramp = new InertiaRamp();
		ramp.step(8.0, 6.0, -3.0, 0.75, true);
		assertEquals(8.0, ramp.x(), 1.0e-9);
		assertEquals(6.0, ramp.y(), 1.0e-9);
		assertEquals(-3.0, ramp.z(), 1.0e-9);
	}

	@Test
	void rampDecaysBackToZeroOnStop() {
		InertiaRamp ramp = new InertiaRamp();
		for (int tick = 0; tick < 20; tick++) {
			ramp.step(8.0, 0.0, 0.0, 0.75, false);
		}
		for (int tick = 0; tick < 30; tick++) {
			ramp.step(0.0, 0.0, 0.0, 0.75, false);
		}
		assertTrue(ramp.x() < 0.01);
	}
}
