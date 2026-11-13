package com.capy.client.finder.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SeedDeepslateTest {
	@Test
	void onlyTheOverworldGradientBandIsJudged() {
		SeedDeepslate deepslate = new SeedDeepslate(1234L);
		assertTrue(deepslate.natural(0, -64, 0));
		assertTrue(deepslate.natural(0, 0, 0));
		assertFalse(deepslate.natural(0, 8, 0));
		assertFalse(deepslate.suspicious(0, 0, 0));
		assertFalse(deepslate.suspicious(0, 8, 0));
	}

	@Test
	void theSameSeedAndPositionAlwaysAgree() {
		SeedDeepslate first = new SeedDeepslate(987654321L);
		SeedDeepslate second = new SeedDeepslate(987654321L);
		for (int y = 1; y < 8; y++) {
			for (int x = -8; x < 8; x++) {
				assertTrue(first.natural(x, y, x) == second.natural(x, y, x));
			}
		}
	}
}
