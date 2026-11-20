package com.capy.client.finder.core;

/**
 * "Beacon" flag core: a pulsing glowing cube made of nested translucent shells with a bright white heart, four corner posts,
 * and a thin beam. It sits on a terrain-hugging chunk plate (TerrainPlate + the contour lines). Quads and lines only.
 */
public final class BeaconCore {
	private BeaconCore() {
	}

	/** One cube rotated by yaw around its center: 6 quads. */
	public static void cube(DrawSink s, double cx, double cy, double cz, double half, double yaw, int argb) {
		double c = Math.cos(yaw), n = Math.sin(yaw);
		double[][] corner = new double[8][3];
		for (int i = 0; i < 8; i++) {
			double dx = (i & 1) == 0 ? -half : half, dy = (i & 2) == 0 ? -half : half, dz = (i & 4) == 0 ? -half : half;
			corner[i][0] = cx + dx * c - dz * n;
			corner[i][1] = cy + dy;
			corner[i][2] = cz + dx * n + dz * c;
		}
		int[][] face = {{0, 1, 3, 2}, {4, 6, 7, 5}, {0, 2, 6, 4}, {1, 5, 7, 3}, {0, 4, 5, 1}, {2, 3, 7, 6}};
		for (int[] f : face) {
			s.quad(corner[f[0]][0], corner[f[0]][1], corner[f[0]][2], corner[f[1]][0], corner[f[1]][1], corner[f[1]][2],
					corner[f[2]][0], corner[f[2]][1], corner[f[2]][2], corner[f[3]][0], corner[f[3]][1], corner[f[3]][2], argb);
		}
	}

	/** Five nested shells only: 30 quads, no posts and no beam. */
	public static void shells(DrawSink s, double x, double y, double z, int rgb, double timeMs, double ease) {
		double e = Math.max(0.0, Math.min(1.0, ease));
		double breathe = 1.0 + 0.12 * Math.sin(timeMs / 380.0), yaw = timeMs / 2200.0;
		double[] size = {0.34, 0.5, 0.78, 1.15, 1.7};
		int[] alpha = {255, 150, 90, 55, 28};
		for (int i = size.length - 1; i >= 0; i--) {
			int color = i == 0 ? 0xFFFFFF : rgb;
			cube(s, x, y, z, size[i] * breathe, yaw + i * 0.15, ChunkStyles.argb((int) (alpha[i] * e), color));
		}
	}

	/** ease 0..1 fades the whole core in. The glow breathes about 12 percent. */
	public static void core(DrawSink s, double x, double y, double z, int rgb, double timeMs, double ease) {
		core(s, x, y, z, rgb, timeMs, ease, true, 60.0);
	}

	/** The same core with the beam optionally suppressed by a module's Beam row. */
	public static void core(DrawSink s, double x, double y, double z, int rgb, double timeMs,
			double ease, boolean beam) {
		core(s, x, y, z, rgb, timeMs, ease, beam, 60.0);
	}

	public static void core(DrawSink s, double x, double y, double z, int rgb, double timeMs,
			double ease, boolean beam, double beamHeight) {
		double e = Math.max(0.0, Math.min(1.0, ease));
		shells(s, x, y, z, rgb, timeMs, e);
		double breathe = 1.0 + 0.12 * Math.sin(timeMs / 380.0), yaw = timeMs / 2200.0;
		double post = 1.3 * breathe;
		for (int i = 0; i < 4; i++) {
			double a = yaw + Math.PI / 2 * i;
			double px = x + Math.cos(a) * post, pz = z + Math.sin(a) * post;
			s.line(px, y - 1.6, pz, px, y + 1.6, pz, 2.0F, ChunkStyles.argb((int) (190 * e), rgb));
		}
		if (beam) {
			ChunkStyles.beam(s, x, z, y + 1.6, y + Math.max(1.0, beamHeight), ChunkStyles.argb((int) (110 * e), rgb));
		}
	}

	/** Tracer brightness by distance: full within 32 blocks, fading to a faint line at 256 and beyond. */
	public static int tracerAlpha(double distance) {
		if (distance <= 32) {
			return 230;
		}
		if (distance >= 256) {
			return 60;
		}
		return (int) Math.round(230 - (distance - 32) / 224.0 * 170);
	}

	public static void tracer(DrawSink s, double fx, double fy, double fz, double tx, double ty, double tz, int rgb) {
		double d = Math.sqrt((tx - fx) * (tx - fx) + (ty - fy) * (ty - fy) + (tz - fz) * (tz - fz));
		s.line(fx, fy, fz, tx, ty, tz, 1.6F, ChunkStyles.argb(tracerAlpha(d), rgb));
	}
}
