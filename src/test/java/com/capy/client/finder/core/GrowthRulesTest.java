package com.capy.client.finder.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GrowthRulesTest {
	@Test
	void immaturePlantsThatCanGrowAreOwed() {
		assertTrue(GrowthRules.kelp(24, true));
		assertFalse(GrowthRules.kelp(25, true));
		assertFalse(GrowthRules.kelp(24, false));

		assertTrue(GrowthRules.bamboo(0, true, 14, 9));
		assertFalse(GrowthRules.bamboo(0, true, 15, 9));
		assertFalse(GrowthRules.bamboo(1, true, 2, 9));

		assertTrue(GrowthRules.sugarCane(true, 2));
		assertFalse(GrowthRules.sugarCane(true, 3));
		assertTrue(GrowthRules.cactus(true, 14, 1));
		assertFalse(GrowthRules.cactus(true, 15, 3));

		assertTrue(GrowthRules.crop(false, 9));
		assertFalse(GrowthRules.crop(true, 15));
		assertFalse(GrowthRules.stem(7, 15));
	}

	@Test
	void eyeblossomWindowsAreExplicit() {
		assertEquals(Boolean.TRUE, GrowthRules.eyeblossomsShouldBeOpen(16600));
		assertNull(GrowthRules.eyeblossomsShouldBeOpen(12601));
		assertEquals(Boolean.FALSE, GrowthRules.eyeblossomsShouldBeOpen(27401));
		assertEquals(4, GrowthRules.eyeblossomWeight(4, 0));
		assertEquals(0, GrowthRules.eyeblossomWeight(4, 1));
	}
}
