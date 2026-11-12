package com.capy.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UiSpritesTest {
	@Test
	void roundedMaskIsClearAtTheCornerAndOpaqueInTheMiddle() {
		assertEquals(0, UiSprites.alphaAt(UiSprites.Kind.PANEL, 64, 20, 0, 0));
		assertEquals(255, UiSprites.alphaAt(UiSprites.Kind.PANEL, 64, 20, 32, 32));
	}

	@Test
	void borderLeavesTheMiddleEmpty() {
		assertEquals(0, UiSprites.alphaAt(UiSprites.Kind.BORDER, 64, 20, 32, 32));
		assertTrue(UiSprites.alphaAt(UiSprites.Kind.BORDER, 64, 20, 0, 32) > 0);
	}

	@Test
	void noiseAndVignetteAreDeterministic() {
		int first = UiSprites.alphaAt(UiSprites.Kind.NOISE, 64, 6, 13, 21);
		int second = UiSprites.alphaAt(UiSprites.Kind.NOISE, 64, 6, 13, 21);
		assertEquals(first, second);
		assertEquals(0, UiSprites.alphaAt(UiSprites.Kind.VIGNETTE, 64, 20, 32, 32));
		assertTrue(UiSprites.alphaAt(UiSprites.Kind.VIGNETTE, 64, 20, 0, 0) > 0);
	}

	@Test
	void nineSliceKeepsCornersAndStretchesOnlyTheCentre() {
		int[] slice = UiSprites.slice(100, 20, 64, 20);
		assertEquals(20, slice[0]);
		assertEquals(60, slice[1]);
		assertEquals(20, slice[2]);
		assertEquals(24, slice[3]);
		assertEquals(60, UiSprites.centreSize(100, 20));
		assertEquals(0, UiSprites.centreSize(40, 20));
	}
}
