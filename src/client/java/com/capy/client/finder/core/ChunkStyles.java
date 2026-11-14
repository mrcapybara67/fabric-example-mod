package com.capy.client.finder.core;

import java.util.Set;

/** Colored chunk styles. Quads and lines only (the pipeline draws quads: 4 vertices per primitive). */
public final class ChunkStyles {
	private ChunkStyles() {
	}

	public static int argb(int alpha, int rgb) {
		return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0xFFFFFF);
	}

	/** FILL_CONTOUR: rippling translucent chunk fill; a glowing outline only on sides that touch an unflagged chunk. */
	public static void fillContour(DrawSink s, Set<Long> chunks, double y, int rgb, int fillAlpha, int edgeAlpha,
			double timeSec) {
		for (long key : chunks) {
			int cx = Chunks.x(key), cz = Chunks.z(key);
			double x0 = cx * 16.0, z0 = cz * 16.0, x1 = x0 + 16, z1 = z0 + 16;
			double wave = 0.72 + 0.28 * Math.sin(timeSec * 1.8 - (cx + cz) * 0.55);
			s.quad(x0, y, z0, x0, y, z1, x1, y, z1, x1, y, z0, argb((int) Math.round(fillAlpha * wave), rgb));
			if (!chunks.contains(Chunks.key(cx, cz - 1))) contour(s, x0, z0, x1, z0, y, rgb, edgeAlpha);
			if (!chunks.contains(Chunks.key(cx, cz + 1))) contour(s, x0, z1, x1, z1, y, rgb, edgeAlpha);
			if (!chunks.contains(Chunks.key(cx - 1, cz))) contour(s, x0, z0, x0, z1, y, rgb, edgeAlpha);
			if (!chunks.contains(Chunks.key(cx + 1, cz))) contour(s, x1, z0, x1, z1, y, rgb, edgeAlpha);
			int faint = argb(edgeAlpha * 40 / 255, rgb);
			if (chunks.contains(Chunks.key(cx + 1, cz))) {
				s.line(x1, y + 0.01, z0, x1, y + 0.01, z1, 1.0F, faint);
			}
			if (chunks.contains(Chunks.key(cx, cz + 1))) {
				s.line(x0, y + 0.01, z1, x1, y + 0.01, z1, 1.0F, faint);
			}
		}
	}

	private static void contour(DrawSink s, double xa, double za, double xb, double zb, double y, int rgb, int a) {
		s.line(xa, y + 0.01, za, xb, y + 0.01, zb, 6.0F, argb(a * 55 / 255, rgb));
		s.line(xa, y + 0.02, za, xb, y + 0.02, zb, 2.0F, argb(a * 235 / 255, rgb));
	}

	/** MARKER: faint chunk, pulsing radial glow, corner brackets, two counter-rotating hexagons, optional beam. ease is 0..1. */
	public static void marker(DrawSink s, int cx, int cz, double y, int rgb, double ease, double timeMs, boolean beam) {
		double x0 = cx * 16.0, z0 = cz * 16.0, x1 = x0 + 16, z1 = z0 + 16, mx = x0 + 8, mz = z0 + 8;
		double pulse = 0.82 + 0.18 * Math.sin(timeMs / 420.0), spin = timeMs / 2600.0;
		s.quad(x0, y, z0, x0, y, z1, x1, y, z1, x1, y, z0, argb((int) (34 * ease), rgb));
		double radius = 8.0 * (0.6 + 0.4 * ease);
		int rings = 6, segs = 24;
		for (int r = 0; r < rings; r++) {
			double in = radius * r / rings, out = radius * (r + 1) / rings;
			int a = (int) (120 * pulse * ease * (1.0 - (r + 0.5) / rings));
			for (int i = 0; i < segs; i++) {
				double a0 = Math.PI * 2 * i / segs, a1 = Math.PI * 2 * (i + 1) / segs;
				s.quad(mx + Math.cos(a0) * in, y + 0.01, mz + Math.sin(a0) * in,
						mx + Math.cos(a0) * out, y + 0.01, mz + Math.sin(a0) * out,
						mx + Math.cos(a1) * out, y + 0.01, mz + Math.sin(a1) * out,
						mx + Math.cos(a1) * in, y + 0.01, mz + Math.sin(a1) * in, argb(a, rgb));
			}
		}
		int edge = argb((int) (235 * ease), rgb);
		double arm = 4.0;
		bracket(s, x0, z0, arm, arm, y, edge);
		bracket(s, x1, z0, -arm, arm, y, edge);
		bracket(s, x1, z1, -arm, -arm, y, edge);
		bracket(s, x0, z1, arm, -arm, y, edge);
		hexagon(s, mx, mz, y + 0.02, 2.4 * ease, spin, argb((int) (255 * ease), rgb));
		hexagon(s, mx, mz, y + 0.02, 1.2 * ease, -spin, argb((int) (170 * pulse * ease), 0xFFFFFF));
		if (beam) beam(s, mx, mz, y, y + 96, argb((int) (140 * ease), rgb));
	}

	private static void bracket(DrawSink s, double x, double z, double dx, double dz, double y, int c) {
		s.line(x, y + 0.02, z, x + dx, y + 0.02, z, 2.5F, c);
		s.line(x, y + 0.02, z, x, y + 0.02, z + dz, 2.5F, c);
	}

	private static void hexagon(DrawSink s, double cx, double cz, double y, double r, double rot, int c) {
		for (int i = 0; i < 6; i++) {
			double a0 = rot + Math.PI / 3 * i, a1 = rot + Math.PI / 3 * (i + 1);
			s.line(cx + Math.cos(a0) * r, y, cz + Math.sin(a0) * r,
					cx + Math.cos(a1) * r, y, cz + Math.sin(a1) * r, 2.0F, c);
		}
	}

	public static void beam(DrawSink s, double x, double z, double y0, double y1, int argb) {
		double w = 0.45;
		s.quad(x - w, y0, z, x + w, y0, z, x + w, y1, z, x - w, y1, z, argb);
		s.quad(x, y0, z - w, x, y0, z + w, x, y1, z + w, x, y1, z - w, argb);
	}

	public static void simple(DrawSink s, int cx, int cz, double y, int argb, boolean beam) {
		double x0 = cx * 16.0, z0 = cz * 16.0, x1 = x0 + 16, z1 = z0 + 16;
		int rgb = argb & 0xFFFFFF, a = argb >>> 24;
		s.quad(x0, y, z0, x1, y, z0, x1, y, z1, x0, y, z1, ChunkStyles.argb(a / 5, rgb));
		int e = ChunkStyles.argb(Math.min(255, a + 40), rgb);
		s.line(x0, y, z0, x1, y, z0, 2.5F, e);
		s.line(x1, y, z0, x1, y, z1, 2.5F, e);
		s.line(x1, y, z1, x0, y, z1, 2.5F, e);
		s.line(x0, y, z1, x0, y, z0, 2.5F, e);
		if (beam) beam(s, x0 + 8, z0 + 8, y, y + 96, ChunkStyles.argb(a * 55 / 100, rgb));
	}
}
