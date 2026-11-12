package com.capy.client.module.modules.esp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ShaftFinderTest {
	private static final class Fake implements ChunkFinderBetterModule.ShaftWorld {
		private final int minY;
		private final int maxY;
		private final int bottom;
		private final int top;
		private final int minX;
		private final int maxX;
		private final int minZ;
		private final int maxZ;

		Fake(int minY, int maxY, int bottom, int top, int minX, int maxX, int minZ, int maxZ) {
			this.minY = minY;
			this.maxY = maxY;
			this.bottom = bottom;
			this.top = top;
			this.minX = minX;
			this.maxX = maxX;
			this.minZ = minZ;
			this.maxZ = maxZ;
		}

		@Override
		public boolean air(int x, int y, int z) {
			return x == 8 && z == 8 && y >= bottom && y <= top;
		}

		@Override
		public boolean solid(int x, int y, int z) {
			if (x == 8 && z == 8) {
				return y == bottom - 1 || y == top + 1;
			}
			if (x >= 7 && x <= 9 && z >= 7 && z <= 9) {
				return true;
			}
			return x < minX || x > maxX || z < minZ || z > maxZ;
		}

		@Override
		public boolean sky(int x, int y, int z) {
			return false;
		}
	}

	@Test
	void findsKnownShaftAtTenToTwenty() {
		Fake world = new Fake(0, 32, 10, 20, 0, 15, 0, 15);
		assertEquals(1, ChunkFinderBetterModule.scanColumn(world, 8, 8, 0, 32, 4, 4));
	}

	@Test
	void shaftBelowBandIsNotCounted() {
		Fake world = new Fake(-64, 32, -60, -50, 0, 15, 0, 15);
		assertEquals(0, ChunkFinderBetterModule.scanColumn(world, 8, 8, 0, 32, 4, 4));
	}

	@Test
	void indexMathUsesRealWorldMinimum() {
		assertEquals(4, ChunkFinderBetterModule.sectionIndex(0, -64));
		assertEquals(0, ChunkFinderBetterModule.sectionIndex(0, 0));
		assertEquals(10, ChunkFinderBetterModule.localY(10, 0));
				assertEquals(15, ChunkFinderBetterModule.localY(-49, -64));
	}
}
