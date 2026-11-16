package com.capy.client.finder.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FlagRenderTest {
	@BeforeEach
	void reset() {
		FlagRender.resetForTests();
	}

	@Test
	void detailThresholdsAreInclusiveAtTheBoundary() {
		assertEquals(FlagRender.Detail.FULL, FlagRender.level(0));
		assertEquals(FlagRender.Detail.FULL, FlagRender.level(160));
		assertEquals(FlagRender.Detail.SIMPLE, FlagRender.level(160.001));
		assertEquals(FlagRender.Detail.SIMPLE, FlagRender.level(320));
		assertEquals(FlagRender.Detail.NONE, FlagRender.level(320.001));
	}

	@Test
	void budgetFallsBackAfterTheFullStylesStopFitting() {
		CountingSink sink = new CountingSink();
		int full = 0;
		for (int i = 0; i < 200; i++) {
			if (FlagRender.reserve(110)) {
				FlagStyles.radarSweep(sink, i, 0, 64, 0x59D8FF, 1000);
				full++;
			}
		}
		assertEquals(54, full);
		assertEquals(54 * 14, sink.quads);
		assertEquals(54 * 85, sink.lines);
		assertEquals(60, FlagRender.remaining());
		assertFalse(FlagRender.reserve(100));
	}

	@Test
	void aFreshFrameRestoresTheFullBudget() {
		assertTrue(FlagRender.reserve(5900));
		assertFalse(FlagRender.reserve(200));
		FlagRender.beginFrame();
		assertEquals(FlagRender.DEFAULT_BUDGET, FlagRender.remaining());
	}

	private static final class CountingSink implements DrawSink {
		int quads;
		int lines;

		@Override
		public void quad(double x0, double y0, double z0, double x1, double y1, double z1,
				double x2, double y2, double z2, double x3, double y3, double z3, int argb) {
			quads++;
		}

		@Override
		public void line(double x0, double y0, double z0, double x1, double y1, double z1,
				float width, int argb) {
			lines++;
		}
	}
}
