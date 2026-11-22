package com.capy.client.module.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AtmosphereRainTest {
	@Test
	void everyThemeHasItsOwnRainColour() {
		Set<Integer> colors = new HashSet<>();
		for (int theme = 0; theme < 10; theme++) {
			colors.add(AtmosphereRain.rainColor(theme));
		}
		assertEquals(10, colors.size());
	}

	@Test
	void tintBoostsAlphaAndKeepsTheThemeColour() {
		int source = 0x40FFFFFF;
		int tinted = AtmosphereRain.tint(source, 6);
		assertTrue(((tinted >>> 24) & 0xFF) > 0x40);
		assertEquals(AtmosphereRain.rainColor(6), tinted & 0x00FFFFFF);
	}

	@Test
	void aFullyTransparentDropStaysTransparent() {
		assertEquals(0, AtmosphereRain.tint(0, 0));
	}

	@Test
	void snowIsNotTintedAsRain() {
		assertTrue(AtmosphereRain.isRain(1.0f));
		assertFalse(AtmosphereRain.isRain(0.8f));
	}
}
