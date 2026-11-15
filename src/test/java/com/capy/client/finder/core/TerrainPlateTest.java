package com.capy.client.finder.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TerrainPlateTest {
	@Test
	void flatHeightsDrawSixtyFourCellsAndNoSkirts() {
		Counter sink = new Counter();
		int[] heights = new int[TerrainPlate.CELLS * TerrainPlate.CELLS];
		java.util.Arrays.fill(heights, 64);

		TerrainPlate.emit(sink, 0, 0, heights, 0x80FF0000, 0.05);

		assertEquals(64, sink.quads);
	}

	@Test
	void aStepDrawsSixtyFourCellsPlusEightSkirtQuads() {
		Counter sink = new Counter();
		int[] heights = new int[TerrainPlate.CELLS * TerrainPlate.CELLS];
		for (int z = 0; z < TerrainPlate.CELLS; z++) {
			for (int x = 0; x < TerrainPlate.CELLS; x++) {
				heights[z * TerrainPlate.CELLS + x] = x < 4 ? 10 : 20;
			}
		}

		TerrainPlate.emit(sink, 0, 0, heights, 0x80FF0000, 0.05);

		assertEquals(72, sink.quads);
	}

	@Test
	void aMalformedHeightArrayDrawsNothing() {
		Counter sink = new Counter();

		TerrainPlate.emit(sink, 0, 0, null, 0xFFFFFFFF, 0.05);
		TerrainPlate.emit(sink, 0, 0, new int[3], 0xFFFFFFFF, 0.05);

		assertEquals(0, sink.quads);
	}

	private static final class Counter implements DrawSink {
		private int quads;

		@Override
		public void quad(double x0, double y0, double z0, double x1, double y1, double z1,
				double x2, double y2, double z2, double x3, double y3, double z3, int argb) {
			quads++;
		}

		@Override
		public void line(double x0, double y0, double z0, double x1, double y1, double z1,
				float width, int argb) {
		}
	}
}
