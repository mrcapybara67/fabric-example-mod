package com.capy.client.finder.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ChunkStylesTest {
	@Test
	void oneChunkDrawsOneQuadAndEightContourLines() {
		Counter sink = new Counter();
		ChunkStyles.fillContour(sink, Set.of(Chunks.key(0, 0)), 64.0, 0x88B8FF, 42, 220, 0.0);

		assertEquals(1, sink.quads);
		assertEquals(8, sink.lines);
	}

	@Test
	void twoAdjacentChunksDrawTwoQuadsAndThirteenLines() {
		Counter sink = new Counter();
		ChunkStyles.fillContour(sink,
				Set.of(Chunks.key(0, 0), Chunks.key(1, 0)), 64.0, 0x88B8FF, 42, 220, 0.0);

		assertEquals(2, sink.quads);
		assertEquals(13, sink.lines);
	}

	private static final class Counter implements DrawSink {
		private int quads;
		private int lines;

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
