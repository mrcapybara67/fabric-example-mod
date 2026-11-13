package com.capy.client.finder.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Vertical shaft finder. A cell is "open" when its block has an empty collision shape.
 * ONE = 1x1 column: open cell, all four horizontal neighbours closed.
 * THREE = 3x1 slot (along X or Z): three open cells in a row, both ends closed, both long sides closed.
 * A run of matching cells at least minDepth tall is a shaft.
 */
public final class ShaftFinder {
	private ShaftFinder() {
	}

	public enum Shape {
		ONE(1, 1),
		THREE_X(3, 1),
		THREE_Z(1, 3);

		public final int sizeX;
		public final int sizeZ;

		Shape(int sizeX, int sizeZ) {
			this.sizeX = sizeX;
			this.sizeZ = sizeZ;
		}
	}

	public record Shaft(int x, int minY, int z, int maxY, Shape shape) {
		public int height() {
			return maxY - minY;
		}
	}

	/** Open flags for one chunk plus a 3-cell border. Unknown cells default to open, so a border never invents a shaft. */
	public static final class OpenMap {
		public static final int PAD = 3;
		public static final int SIDE = 16 + 2 * PAD;

		private final int minY;
		private final int height;
		private final boolean[] open;

		public OpenMap(int minY, int height) {
			this.minY = minY;
			this.height = height;
			this.open = new boolean[SIDE * SIDE * height];
			Arrays.fill(open, true);
		}

		private int index(int lx, int y, int lz) {
			return ((y - minY) * SIDE + (lz + PAD)) * SIDE + (lx + PAD);
		}

		private boolean inside(int lx, int y, int lz) {
			return y >= minY && y < minY + height && lx >= -PAD && lx < 16 + PAD
					&& lz >= -PAD && lz < 16 + PAD;
		}

		public void set(int lx, int y, int lz, boolean isOpen) {
			if (inside(lx, y, lz)) {
				open[index(lx, y, lz)] = isOpen;
			}
		}

		public boolean open(int lx, int y, int lz) {
			return !inside(lx, y, lz) || open[index(lx, y, lz)];
		}

		public int minY() {
			return minY;
		}

		public int maxY() {
			return minY + height - 1;
		}
	}

	public static List<Shaft> find(OpenMap m, int baseX, int baseZ, int minDepth, boolean one, boolean three) {
		List<Shaft> out = new ArrayList<>();
		if (!one && !three) {
			return out;
		}
		for (int lx = 0; lx < 16; lx++) {
			for (int lz = 0; lz < 16; lz++) {
				if (one) {
					column(m, out, baseX, baseZ, lx, lz, minDepth, Shape.ONE);
				}
				if (three) {
					column(m, out, baseX, baseZ, lx, lz, minDepth, Shape.THREE_X);
					column(m, out, baseX, baseZ, lx, lz, minDepth, Shape.THREE_Z);
				}
			}
		}
		return out;
	}

	private static void column(OpenMap m, List<Shaft> out, int bx, int bz, int lx, int lz, int depth, Shape shape) {
		int run = 0;
		int top = m.maxY();
		for (int y = m.minY(); y <= top + 1; y++) {
			if (y <= top && matches(m, lx, y, lz, shape)) {
				run++;
			} else {
				if (run >= depth) {
					out.add(new Shaft(bx + lx, y - run, bz + lz, y, shape));
				}
				run = 0;
			}
		}
	}

	static boolean matches(OpenMap m, int x, int y, int z, Shape s) {
		return switch (s) {
			case ONE -> m.open(x, y, z) && !m.open(x - 1, y, z) && !m.open(x + 1, y, z)
					&& !m.open(x, y, z - 1) && !m.open(x, y, z + 1);
			case THREE_X -> m.open(x, y, z) && m.open(x + 1, y, z) && m.open(x + 2, y, z)
					&& !m.open(x - 1, y, z) && !m.open(x + 3, y, z)
					&& !m.open(x, y, z - 1) && !m.open(x, y, z + 1)
					&& !m.open(x + 1, y, z - 1) && !m.open(x + 1, y, z + 1)
					&& !m.open(x + 2, y, z - 1) && !m.open(x + 2, y, z + 1);
			case THREE_Z -> m.open(x, y, z) && m.open(x, y, z + 1) && m.open(x, y, z + 2)
					&& !m.open(x, y, z - 1) && !m.open(x, y, z + 3)
					&& !m.open(x - 1, y, z) && !m.open(x + 1, y, z)
					&& !m.open(x - 1, y, z + 1) && !m.open(x + 1, y, z + 1)
					&& !m.open(x - 1, y, z + 2) && !m.open(x + 1, y, z + 2);
		};
	}
}
