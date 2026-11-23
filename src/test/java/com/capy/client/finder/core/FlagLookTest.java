package com.capy.client.finder.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FlagLookTest {
	private static final int SURFACE = 65;
	private static final long KEY = Chunks.key(0, 0);

	@BeforeEach
	void reset() {
		FlagRender.resetForTests();
	}

	@Test
	void plateRimAndCoreUseDifferentLiftsInOrder() {
		Counter sink = new Counter();
		FlagLook.drawAll(sink, List.of(fullFlag()), 1_000, FlagQuality.HIGH, false, false, null);

		assertEquals(65.01, sink.firstQuadY, 1.0e-9, "plate sits one hundredth above the surface");
		assertEquals(65.03, sink.firstLineY, 1.0e-9, "rim sits above the plate");
		assertTrue(sink.maxQuadY > 66.5, "the core rises above the rim");
		assertEquals(96, sink.quads, "64 plate quads plus 32 core quads");
		assertEquals(12, sink.lines, "8 rim lines plus 4 posts");
	}

	@Test
	void lowKeepsOnlyThePlateAndRim() {
		Counter sink = new Counter();
		FlagLook.drawAll(sink, List.of(fullFlag()), 1_000, FlagQuality.LOW, false, false, null);
		assertEquals(64, sink.quads);
		assertEquals(8, sink.lines);
	}

	@Test
	void balancedOmitsPostsAndBeam() {
		Counter sink = new Counter();
		FlagLook.drawAll(sink, List.of(fullFlag()), 1_000, FlagQuality.BALANCED, false, false, null);
		assertEquals(94, sink.quads, "64 plate quads plus 30 shell quads");
		assertEquals(8, sink.lines, "rim only, no posts");
	}

	@Test
	void theSimpleDistanceAlsoOmitsPostsAndBeam() {
		Counter sink = new Counter();
		FlagLook.Flag flag = FlagLook.Flag.chunk(KEY, flat(), 0xFF4466, 200, 1, false);
		FlagLook.drawAll(sink, List.of(flag), 1_000, FlagQuality.HIGH, false, false, null);
		assertEquals(94, sink.quads);
		assertEquals(8, sink.lines);
	}

	@Test
	void nothingIsDrawnBeyond320() {
		Counter sink = new Counter();
		FlagLook.Flag flag = FlagLook.Flag.chunk(KEY, flat(), 0xFF4466, 320.001, 1, false);
		FlagLook.drawAll(sink, List.of(flag), 1_000, FlagQuality.HIGH, false, true, new double[]{0, 70, 0});
		assertEquals(0, sink.quads);
		assertEquals(0, sink.lines);
	}

	@Test
	void autoLowerDropsHighToShellsAndBalancedToBase() {
		assertEquals(FlagLook.Layer.SHELLS, FlagLook.layer(100, FlagQuality.HIGH, true));
		assertEquals(FlagLook.Layer.BASE, FlagLook.layer(100, FlagQuality.BALANCED, true));
		assertEquals(FlagLook.Layer.BASE, FlagLook.layer(100, FlagQuality.LOW, true));
	}

	@Test
	void lodThresholdsAreInclusiveAtFullAndSimple() {
		assertEquals(FlagLook.Layer.FULL, FlagLook.layer(160, FlagQuality.HIGH, false));
		assertEquals(FlagLook.Layer.SHELLS, FlagLook.layer(160.001, FlagQuality.HIGH, false));
		assertEquals(FlagLook.Layer.SHELLS, FlagLook.layer(320, FlagQuality.HIGH, false));
		assertEquals(FlagLook.Layer.NONE, FlagLook.layer(320.001, FlagQuality.HIGH, false));
	}

	@Test
	void depthFlagKeepsItsCoreAtTheEventHeight() {
		Counter sink = new Counter();
		FlagLook.Flag flag = FlagLook.Flag.atDepth(KEY, 8, -40, 8, flat(), 0xFF4466, 20, 1, false, true);
		assertEquals(-40, flag.coreY(), 1.0e-9);
		FlagLook.drawAll(sink, List.of(flag), 1_000, FlagQuality.HIGH, false, false, null);
		assertTrue(sink.maxQuadY > SURFACE, "the surface plate is still drawn");
		assertTrue(sink.minQuadY < -39, "the core is drawn at the deep event");
	}

	@Test
	void theBudgetFallsBackToPlateAndRimOnly() {
		assertTrue(FlagRender.reserve(FlagRender.DEFAULT_BUDGET));
		Counter sink = new Counter();
		FlagLook.drawAll(sink, List.of(fullFlag()), 1_000, FlagQuality.HIGH, false, false, null);
		assertEquals(64, sink.quads, "no core fits after the budget is exhausted");
		assertEquals(8, sink.lines, "rim only");
	}

	@Test
	void tracersAreCappedAtThreeNearestFlags() {
		Counter sink = new Counter();
		List<FlagLook.Flag> flags = new ArrayList<>();
		for (int i = 1; i <= 5; i++) {
			flags.add(FlagLook.Flag.coreOnly(i * 10.0, 70, 0, 0xFF4466, i * 10.0, 1, true));
		}
		FlagLook.drawAll(sink, flags, 1_000, FlagQuality.HIGH, false, true, new double[]{0, 70, 0});
		assertEquals(160, sink.quads, "five full cores");
		assertEquals(20 + 3, sink.lines, "four posts per core plus three tracers");
		assertEquals(3, sink.tracerLines);
	}

	@Test
	void theTaskThreeSetIsExplicitAndTheExcludedModulesAreNotInIt() {
		for (String id : List.of("stalled_growth_finder", "hive_finder", "chunk_scanner",
				"deepslate_finder", "motion_scanner", "sound_bypass", "activity_inspector",
				"bedrock_void", "artificial_light", "light_finder", "shaft_finder")) {
			assertTrue(FlagLook.isChunkFlagModule(id), id);
		}
		for (String id : List.of("amethyst_finder", "amethyst_bypass", "spawner_beam",
				"storage_esp", "block_notifier", "suspicious_esp")) {
			assertFalse(FlagLook.isChunkFlagModule(id), id);
		}
	}

	private static FlagLook.Flag fullFlag() {
		return FlagLook.Flag.chunk(KEY, flat(), 0xFF4466, 20, 1, false);
	}

	private static int[] flat() {
		int[] heights = new int[TerrainPlate.CELLS * TerrainPlate.CELLS];
		Arrays.fill(heights, SURFACE);
		return heights;
	}

	private static final class Counter implements DrawSink {
		int quads;
		int lines;
		int tracerLines;
		double firstQuadY = Double.NaN;
		double firstLineY = Double.NaN;
		double minQuadY = Double.POSITIVE_INFINITY;
		double maxQuadY = Double.NEGATIVE_INFINITY;

		@Override
		public void quad(double x0, double y0, double z0, double x1, double y1, double z1,
				double x2, double y2, double z2, double x3, double y3, double z3, int argb) {
			quads++;
			if (Double.isNaN(firstQuadY)) {
				firstQuadY = y0;
			}
			minQuadY = Math.min(minQuadY, Math.min(Math.min(y0, y1), Math.min(y2, y3)));
			maxQuadY = Math.max(maxQuadY, Math.max(Math.max(y0, y1), Math.max(y2, y3)));
		}

		@Override
		public void line(double x0, double y0, double z0, double x1, double y1, double z1,
				float width, int argb) {
			lines++;
			if (Double.isNaN(firstLineY)) {
				firstLineY = y0;
			}
			if (width == 1.6F) {
				tracerLines++;
			}
		}
	}
}
