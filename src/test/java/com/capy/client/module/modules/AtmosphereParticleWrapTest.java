package com.capy.client.module.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AtmosphereParticleWrapTest {
	@Test
	void wrappingStaysInsideTheBoxOnEveryAxis() {
		for (int i = -100; i <= 100; i++) {
			double wrapped = AtmosphereParticles.wrapInto(i * 13.0, 0.0, 22.0);
			assertTrue(wrapped >= -22.0 && wrapped <= 22.0, "outside x box: " + wrapped);
		}
		assertEquals(0.0, AtmosphereParticles.wrapInto(44.0, 0.0, 22.0), 0.0);
		assertEquals(0.0, AtmosphereParticles.wrapInto(-44.0, 0.0, 22.0), 0.0);
	}

	@Test
	void distanceWrappingWorksAtLargeWorldCoordinates() {
		double camera = 4300.5;
		double wrapped = AtmosphereParticles.wrapInto(-4137.0, camera, 22.0);
		assertTrue(wrapped >= camera - 22.0 && wrapped <= camera + 22.0);
	}

	@Test
	void noFullAlphaWithinOneBlockOfTheCamera() {
		assertEquals(0.0f, AtmosphereParticles.nearFade(1.0), 0.0001f);
		assertTrue(AtmosphereParticles.nearFade(1.5) < AtmosphereParticles.nearFade(2.2));
	}
}
