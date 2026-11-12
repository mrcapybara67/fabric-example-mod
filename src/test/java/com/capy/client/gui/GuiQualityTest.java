package com.capy.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GuiQualityTest {
	@Test
	void parsesNamesCaseInsensitivelyAndFallsBack() {
		assertEquals(GuiQuality.HIGH, GuiQuality.from("high", GuiQuality.BALANCED));
		assertEquals(GuiQuality.LOW, GuiQuality.from("LOW", GuiQuality.BALANCED));
		assertEquals(GuiQuality.BALANCED, GuiQuality.from("ultra", GuiQuality.BALANCED));
		assertEquals(GuiQuality.BALANCED, GuiQuality.from(null, GuiQuality.BALANCED));
	}

	@Test
	void effectsFollowTheQualityBudget() {
		assertTrue(GuiQuality.HIGH.sheen());
		assertFalse(GuiQuality.BALANCED.sheen());
		assertTrue(GuiQuality.BALANCED.noise());
		assertFalse(GuiQuality.LOW.noise());
		assertFalse(GuiQuality.LOW.glow());
		assertEquals(0, GuiQuality.LOW.shadowLayers());
		assertEquals(1, GuiQuality.BALANCED.shadowLayers());
		assertEquals(3, GuiQuality.HIGH.shadowLayers());
	}
}
