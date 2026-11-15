package com.capy.client.finder.core;

/**
 * Draws a chunk plate that follows the terrain instead of the player.
 *
 * <p>The chunk is split into an 8x8 grid of 2x2-block cells. Each cell is a
 * horizontal quad at its own surface height, and a vertical skirt is inserted
 * wherever two adjacent cells differ, so a cliff reads as a cliff rather than
 * as a floating shelf. Heights are supplied by the caller; this class never
 * reads a heightmap itself.</p>
 */
public final class TerrainPlate {
	public static final int CELLS = 8;
	public static final int CELL_SIZE = 2;

	private TerrainPlate() {
	}

	/**
	 * Draws one chunk plate.
	 *
	 * @param heights {@code heights[cellZ * 8 + cellX]} is the surface height
	 *                (the top of the highest solid block) for that cell, in
	 *                world coordinates
	 */
	public static void emit(DrawSink s, int cx, int cz, int[] heights, int argb, double lift) {
		if (heights == null || heights.length != CELLS * CELLS) {
			return;
		}
		int skirt = ChunkStyles.argb((argb >>> 24) * 6 / 10, argb & 0xFFFFFF);
		double ox = cx * 16.0;
		double oz = cz * 16.0;
		for (int cellZ = 0; cellZ < CELLS; cellZ++) {
			for (int cellX = 0; cellX < CELLS; cellX++) {
				double h = heights[cellZ * CELLS + cellX] + lift;
				double x0 = ox + cellX * CELL_SIZE;
				double z0 = oz + cellZ * CELL_SIZE;
				double x1 = x0 + CELL_SIZE;
				double z1 = z0 + CELL_SIZE;
				s.quad(x0, h, z0, x0, h, z1, x1, h, z1, x1, h, z0, argb);
				if (cellX + 1 < CELLS) {
					double nh = heights[cellZ * CELLS + cellX + 1] + lift;
					if (nh != h) {
						s.quad(x1, Math.min(h, nh), z0, x1, Math.min(h, nh), z1,
								x1, Math.max(h, nh), z1, x1, Math.max(h, nh), z0, skirt);
					}
				}
				if (cellZ + 1 < CELLS) {
					double nh = heights[(cellZ + 1) * CELLS + cellX] + lift;
					if (nh != h) {
						s.quad(x0, Math.min(h, nh), z1, x1, Math.min(h, nh), z1,
								x1, Math.max(h, nh), z1, x0, Math.max(h, nh), z1, skirt);
					}
				}
			}
		}
	}

	/** Average of a valid height array, or {@link Double#NaN} when it is malformed. */
	public static double average(int[] heights) {
		if (heights == null || heights.length != CELLS * CELLS) {
			return Double.NaN;
		}
		int sum = 0;
		for (int height : heights) {
			sum += height;
		}
		return sum / (double) (CELLS * CELLS);
	}
}
