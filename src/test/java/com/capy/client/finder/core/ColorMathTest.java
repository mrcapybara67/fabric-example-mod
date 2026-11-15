package com.capy.client.finder.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ColorMathTest {
	@Test
	void hsvRoundTripsSampleColors() {
		for (int color : new int[]{0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFF808080,
				0x12345678, 0xFFFFFFFF, 0xFF000000}) {
			float[] hsv = ColorMath.hsv(color);
			int round = ColorMath.fromHsv(hsv[0], hsv[1], hsv[2], color >>> 24);
			assertEquals(color, round);
		}
	}

	@Test
	void hexFormatsAndParsesWithAndWithoutHash() {
		assertEquals("#123456", ColorMath.hex(0xFF123456, false));
		assertEquals("#FF123456", ColorMath.hex(0xFF123456, true));
		assertEquals(0xFF123456, ColorMath.parseHex("#123456", 0xFF000000));
		assertEquals(0xFF123456, ColorMath.parseHex("123456", 0xFF000000));
		assertEquals(0xFFAABBCC, ColorMath.parseHex("ABC", 0xFF000000));
		assertEquals(0x80123456, ColorMath.parseHex("#80123456", 0));
	}

	@Test
	void invalidHexReturnsTheFallback() {
		int fallback = 0xFF010203;
		assertEquals(fallback, ColorMath.parseHex(null, fallback));
		assertEquals(fallback, ColorMath.parseHex("nope", fallback));
		assertEquals(fallback, ColorMath.parseHex("#12345", fallback));
		assertEquals(fallback, ColorMath.parseHex("#GGGGGG", fallback));
	}

	@Test
	void rainbowChangesHueAndKeepsAlpha() {
		int base = 0x80FF0000;
		int first = ColorMath.eval(ColorMath.Mode.RAINBOW, base, 0xFFFFFFFF, 0.0, 1.0);
		int second = ColorMath.eval(ColorMath.Mode.RAINBOW, base, 0xFFFFFFFF, 2.0, 1.0);
		assertNotEquals(first & 0xFFFFFF, second & 0xFFFFFF);
		assertEquals(0x80, first >>> 24);
		assertEquals(0x80, second >>> 24);
	}

	@Test
	void pulseKeepsAlphaBetweenFiftyFiveAndOneHundredPercent() {
		int base = 255 << 24 | 0x112233;
		for (int i = 0; i < 120; i++) {
			int alpha = ColorMath.eval(ColorMath.Mode.PULSE, base, 0, i * 0.25, 1.0) >>> 24;
			assertTrue(alpha >= 140 && alpha <= 255, "alpha " + alpha);
		}
	}

	@Test
	void gradientMidpointIsTheBase() {
		int base = 0xFF102030;
		int second = 0xFFA0B0C0;
		// sin(0) = 0, so t = 0.5 at time zero.
		assertEquals(ColorMath.lerp(base, second, 0.5),
				ColorMath.eval(ColorMath.Mode.GRADIENT, base, second, 0, 1.0));
	}

	@Test
	void svSquareCornersBehave() {
		int[] px = ColorMath.svSquare(0f, 8);
		assertEquals(0xFFFFFFFF, px[0]);
		assertEquals(0xFFFF0000, px[7]);
		for (int x = 0; x < 8; x++) {
			assertEquals(0xFF000000, px[7 * 8 + x]);
		}
	}
}
