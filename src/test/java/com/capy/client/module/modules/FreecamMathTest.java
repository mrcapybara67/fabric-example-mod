package com.capy.client.module.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FreecamMathTest {
	@Test
	void positionInterpolatesBetweenPreviousAndCurrentTick() {
		assertEquals(10.0, AtmosphereMath.interpolate(0.0, 20.0, 0.5f), 0.0001);
		assertEquals(0.0, AtmosphereMath.interpolate(0.0, 20.0, 0.0f), 0.0001);
		assertEquals(20.0, AtmosphereMath.interpolate(0.0, 20.0, 1.0f), 0.0001);
	}

	@Test
	void inertiaKeepsMostOfTheVelocityAtTheDefault() {
		assertEquals(0.4, AtmosphereMath.inertia(0.0, 1.0, 0.6), 0.0001);
		assertEquals(0.6, AtmosphereMath.inertia(1.0, 0.0, 0.6), 0.0001);
	}

	@Test
	void inertiaIsClampedSoTheCameraCannotRunAway() {
		assertEquals(0.1, AtmosphereMath.inertia(0.0, 1.0, 1.0), 0.0001);
		assertEquals(1.0, AtmosphereMath.inertia(0.0, 1.0, -1.0), 0.0001);
	}
}
