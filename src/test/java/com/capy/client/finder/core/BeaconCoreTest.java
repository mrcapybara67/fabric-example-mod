package com.capy.client.finder.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BeaconCoreTest {

	@Test
	void aCubeIsSixQuads() {
		Counter sink = new Counter();
		BeaconCore.cube(sink, 8, 64, 8, 1, 0.3, 0xFFFFFFFF);
		assertEquals(6, sink.quads);
		assertEquals(0, sink.lines);
	}

	@Test
	void aCoreIsFiveShellsPlusTwoBeamQuadsAndFourPosts() {
		Counter sink = new Counter();
		BeaconCore.core(sink, 8, 64, 8, 0xFF4466, 1_000, 1.0);
		assertEquals(32, sink.quads);
		assertEquals(4, sink.lines);
	}

	@Test
	void everyCoreCoordinateStaysFiniteOverTime() {
		Counter sink = new Counter();
		for (int i = 0; i < 40; i++) {
			BeaconCore.core(sink, 0, 64, 0, 0xFF4466, i * 137.0, i / 39.0);
		}
		assertTrue(sink.finite);
	}

	@Test
	void tracerAlphaHugsTheEndsAndFallsInBetween() {
		assertEquals(230, BeaconCore.tracerAlpha(0));
		assertEquals(230, BeaconCore.tracerAlpha(32));
		int middle = BeaconCore.tracerAlpha(144);
		assertTrue(middle < 230 && middle > 60);
		assertEquals(60, BeaconCore.tracerAlpha(256));
		assertEquals(60, BeaconCore.tracerAlpha(900));
	}

	@Test
	void aTracerIsOneLine() {
		Counter sink = new Counter();
		BeaconCore.tracer(sink, 0, 65, 0, 8, 66, 8, 0xFF4466);
		assertEquals(1, sink.lines);
		assertEquals(0, sink.quads);
	}

	private static final class Counter implements DrawSink {
		int quads;
		int lines;
		boolean finite = true;

		@Override
		public void quad(double x0, double y0, double z0, double x1, double y1, double z1,
				double x2, double y2, double z2, double x3, double y3, double z3, int argb) {
			quads++;
			finite &= finite(x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3);
		}

		@Override
		public void line(double x0, double y0, double z0, double x1, double y1, double z1,
				float width, int argb) {
			lines++;
			finite &= finite(x0, y0, z0, x1, y1, z1) && Float.isFinite(width);
		}

		private static boolean finite(double... values) {
			for (double value : values) {
				if (!Double.isFinite(value)) {
					return false;
				}
			}
			return true;
		}
	}
}
