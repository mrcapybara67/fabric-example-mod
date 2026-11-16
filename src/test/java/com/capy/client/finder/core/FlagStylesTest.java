package com.capy.client.finder.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FlagStylesTest {
	private static final int RGB = 0x59D8FF;

	@Test
	void sonarEmitsBoundedFiniteQuadsAndLines() {
		RecordingSink sink = new RecordingSink();
		FlagStyles.sonar(sink, 1, 64, 2, RGB, 6, 0.4, 1.2);
		assertTrue(sink.quads <= 80, "sonar quads");
		assertTrue(sink.lines <= 130, "sonar lines");
		assertTrue(sink.quads > 0 && sink.lines > 0);
		assertTrue(sink.finite, "sonar must be finite");
	}

	@Test
	void sonarPrimitiveCountDoesNotChangeWithTime() {
		RecordingSink first = new RecordingSink();
		RecordingSink later = new RecordingSink();
		FlagStyles.sonar(first, 0, 0, 0, RGB, 6, 0, 1.2);
		FlagStyles.sonar(later, 0, 0, 0, RGB, 6, 0.85, 1.2);
		assertEquals(first.quads, later.quads);
		assertEquals(first.lines, later.lines);
	}

	@Test
	void radarSweepHasFixedPrimitiveCount() {
		RecordingSink sink = new RecordingSink();
		FlagStyles.radarSweep(sink, 3, -9, 70, RGB, 1234);
		assertEquals(14, sink.quads);
		assertEquals(85, sink.lines);
		assertTrue(sink.finite);
	}

	@Test
	void depthGaugeDrawsTicksChevronsAndOnePulseQuad() {
		RecordingSink sink = new RecordingSink();
		FlagStyles.depthGauge(sink, 4.5, 6.5, 70, 20, RGB, 900);
		assertEquals(1, sink.quads);
		assertTrue(sink.lines >= 13);
		assertTrue(sink.finite);
	}

	@Test
	void zeroHeightDepthGaugeIsSafe() {
		RecordingSink sink = new RecordingSink();
		FlagStyles.depthGauge(sink, 0, 0, 12, 12, RGB, 0);
		assertEquals(1, sink.quads);
		assertTrue(sink.finite);
	}

	@Test
	void honeycombIsLineOnlyAndBounded() {
		RecordingSink sink = new RecordingSink();
		FlagStyles.honeycomb(sink, 0, 0, 64, RGB, 100);
		assertEquals(0, sink.quads);
		assertTrue(sink.lines >= 100 && sink.lines <= 220, "honeycomb lines");
		assertTrue(sink.finite);
	}

	@Test
	void stratumScanIsFiniteAndBounded() {
		RecordingSink sink = new RecordingSink();
		FlagStyles.stratumScan(sink, 0, 0, 8.08, RGB, 700);
		assertTrue(sink.quads <= 8);
		assertTrue(sink.lines <= 12);
		assertTrue(sink.finite);
	}

	@Test
	void bloomIsFiniteAndBounded() {
		RecordingSink sink = new RecordingSink();
		FlagStyles.bloom(sink, 0.5, 64, 0.5, RGB, 700);
		assertTrue(sink.quads <= 48);
		assertTrue(sink.lines <= 120);
		assertTrue(sink.finite);
	}

	@Test
	void vortexIsFiniteAndBounded() {
		RecordingSink sink = new RecordingSink();
		FlagStyles.vortex(sink, 0, -60, 0, RGB, 700);
		assertTrue(sink.quads == 0);
		assertTrue(sink.lines <= 110);
		assertTrue(sink.finite);
	}

	@Test
	void cornerTicksAreFiniteAndBounded() {
		RecordingSink sink = new RecordingSink();
		FlagStyles.cornerTicks(sink, 10, 20, 30, 0.5, RGB, 700);
		assertTrue(sink.quads <= 2);
		assertTrue(sink.lines <= 10);
		assertTrue(sink.finite);
	}

	@Test
	void helixBeamIsFiniteAndBounded() {
		RecordingSink sink = new RecordingSink();
		FlagStyles.helixBeam(sink, 0, 0, 10, 106, RGB, 700);
		assertTrue(sink.quads == 0);
		assertTrue(sink.lines <= 110);
		assertTrue(sink.finite);
	}

	@Test
	void heatRingIsFiniteAndBounded() {
		RecordingSink sink = new RecordingSink();
		FlagStyles.heatRing(sink, 0, 0, 0, 6, RGB, 12, 700);
		assertTrue(sink.quads <= 2);
		assertTrue(sink.lines <= 60);
		assertTrue(sink.finite);
	}

	@Test
	void everyStyleKeepsAFixedCountAcrossSeveralTimes() {
		assertStable((sink, time) -> FlagStyles.stratumScan(sink, 1, 1, 8, RGB, time));
		assertStable((sink, time) -> FlagStyles.bloom(sink, 0, 0, 0, RGB, time));
		assertStable((sink, time) -> FlagStyles.vortex(sink, 0, 0, 0, RGB, time));
		assertStable((sink, time) -> FlagStyles.cornerTicks(sink, 0, 0, 0, 0.5, RGB, time));
		assertStable((sink, time) -> FlagStyles.helixBeam(sink, 0, 0, 10, 106, RGB, time));
		assertStable((sink, time) -> FlagStyles.heatRing(sink, 0, 0, 0, 6, RGB, 12, time));
	}

	private static void assertStable(Style style) {
		int quads = -1;
		int lines = -1;
		for (double time : new double[]{0, 333, 987, 7400}) {
			RecordingSink sink = new RecordingSink();
			style.draw(sink, time);
			assertTrue(sink.finite);
			if (quads < 0) {
				quads = sink.quads;
				lines = sink.lines;
			} else {
				assertEquals(quads, sink.quads, "quads at " + time);
				assertEquals(lines, sink.lines, "lines at " + time);
			}
		}
	}

	@FunctionalInterface
	private interface Style {
		void draw(RecordingSink sink, double time);
	}

	private static final class RecordingSink implements DrawSink {
		int quads;
		int lines;
		boolean finite = true;

		@Override
		public void quad(double x0, double y0, double z0, double x1, double y1, double z1,
				double x2, double y2, double z2, double x3, double y3, double z3, int argb) {
			quads++;
			check(x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3);
		}

		@Override
		public void line(double x0, double y0, double z0, double x1, double y1, double z1,
				float width, int argb) {
			lines++;
			check(x0, y0, z0, x1, y1, z1);
			if (!Float.isFinite(width)) {
				finite = false;
			}
		}

		private void check(double... values) {
			for (double value : values) {
				if (!Double.isFinite(value)) {
					finite = false;
				}
			}
		}
	}
}
