package com.capy.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GuiSizeTest {
	@Test
	void presetsScaleTheWholeInterfaceInBothDirections() {
		assertTrue(GuiSize.SMALL.scale() < GuiSize.NORMAL.scale());
		assertTrue(GuiSize.LARGE.scale() > GuiSize.NORMAL.scale());
		assertEquals(1f, GuiSize.NORMAL.scale(), 1.0e-6f);
	}

	@Test
	void fromAcceptsStoredNamesCaseInsensitivelyAndFallsBack() {
		assertEquals(GuiSize.LARGE, GuiSize.from("large", GuiSize.NORMAL));
		assertEquals(GuiSize.SMALL, GuiSize.from("SMALL", GuiSize.NORMAL));
		assertEquals(GuiSize.NORMAL, GuiSize.from("missing", GuiSize.NORMAL));
		assertEquals(GuiSize.NORMAL, GuiSize.from(null, GuiSize.NORMAL));
	}
}
