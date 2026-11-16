package com.capy.client.finder.core;

/** Distinct, animated flag looks. Quads and lines only. World coordinates in. */
public final class FlagStyles {
	private FlagStyles() {
	}

	private static void ring(DrawSink s, double cx, double y, double cz, double r, int segs, float width, int argb) {
		for (int i = 0; i < segs; i++) {
			double a0 = Math.PI * 2 * i / segs, a1 = Math.PI * 2 * (i + 1) / segs;
			s.line(cx + Math.cos(a0) * r, y, cz + Math.sin(a0) * r,
					cx + Math.cos(a1) * r, y, cz + Math.sin(a1) * r, width, argb);
		}
	}

	/** SONAR (Sound Bypass, Motion Scanner): three ripple rings expanding from the event, fading as they grow, plus a center diamond and a short mast. */
	public static void sonar(DrawSink s, double x, double y, double z, int rgb, double maxRadius, double ageMs, double periodMs) {
		for (int i = 0; i < 3; i++) {
			double phase = (ageMs / periodMs + i / 3.0) % 1.0, fade = Math.pow(1.0 - phase, 1.5);
			double r = maxRadius * phase;
			ring(s, x, y + 0.05, z, r, 40, 2.0F, ChunkStyles.argb((int) (210 * fade), rgb));
			double inner = Math.max(0, r - maxRadius * 0.12);
			for (int k = 0; k < 24; k++) {
				double a0 = Math.PI * 2 * k / 24, a1 = Math.PI * 2 * (k + 1) / 24;
				s.quad(x + Math.cos(a0) * inner, y + 0.03, z + Math.sin(a0) * inner,
						x + Math.cos(a0) * r, y + 0.03, z + Math.sin(a0) * r,
						x + Math.cos(a1) * r, y + 0.03, z + Math.sin(a1) * r,
						x + Math.cos(a1) * inner, y + 0.03, z + Math.sin(a1) * inner,
						ChunkStyles.argb((int) (60 * fade), rgb));
			}
		}
		double d = 0.35;
		s.quad(x - d, y + 0.1, z, x, y + 0.1, z + d, x + d, y + 0.1, z,
				x, y + 0.1, z - d, ChunkStyles.argb(255, rgb));
		s.line(x, y, z, x, y + 3.0, z, 2.0F, ChunkStyles.argb(200, rgb));
	}

	/** RADAR SWEEP (Sus Chunk): ring with tick marks and a rotating wedge that fades behind its leading edge. */
	public static void radarSweep(DrawSink s, int cx, int cz, double y, int rgb, double timeMs) {
		double mx = cx * 16.0 + 8, mz = cz * 16.0 + 8, radius = 7.5,
				lead = (timeMs / 2400.0 % 1.0) * Math.PI * 2, span = Math.PI / 2.4;
		ring(s, mx, y + 0.03, mz, radius, 48, 2.0F, ChunkStyles.argb(210, rgb));
		ring(s, mx, y + 0.03, mz, radius * 0.5, 32, 1.0F, ChunkStyles.argb(90, rgb));
		for (int t = 0; t < 4; t++) {
			double a = Math.PI / 2 * t;
			s.line(mx + Math.cos(a) * radius * 0.88, y + 0.03, mz + Math.sin(a) * radius * 0.88,
					mx + Math.cos(a) * radius, y + 0.03, mz + Math.sin(a) * radius,
					2.0F, ChunkStyles.argb(230, rgb));
		}
		int steps = 14;
		for (int i = 0; i < steps; i++) {
			double a0 = lead - span * i / steps, a1 = lead - span * (i + 1) / steps;
			int alpha = (int) (150 * Math.pow(1.0 - (i + 0.5) / steps, 1.6));
			s.quad(mx, y + 0.04, mz, mx + Math.cos(a0) * radius, y + 0.04, mz + Math.sin(a0) * radius,
					mx + Math.cos(a1) * radius, y + 0.04, mz + Math.sin(a1) * radius,
					mx, y + 0.04, mz, ChunkStyles.argb(alpha, rgb));
		}
		s.line(mx, y + 0.05, mz, mx + Math.cos(lead) * radius, y + 0.05, mz + Math.sin(lead) * radius,
				2.5F, ChunkStyles.argb(255, rgb));
	}

	/** DEPTH GAUGE (Tunnel Hole ESP, Chunk Scanner, Sound Bypass): a mast with a tick every 4 blocks, a chevron every 8, and a light pulse running down it. */
	public static void depthGauge(DrawSink s, double x, double z, double yTop, double yBottom, int rgb, double timeMs) {
		double top = Math.max(yTop, yBottom);
		double bottom = Math.min(yTop, yBottom);
		s.line(x, bottom, z, x, top, z, 2.5F, ChunkStyles.argb(220, rgb));
		for (double y = Math.ceil(bottom / 4) * 4; y <= top; y += 4) {
			boolean major = ((long) Math.round(y) % 8) == 0;
			double half = major ? 0.7 : 0.4;
			s.line(x - half, y, z, x + half, y, z, major ? 2.0F : 1.0F,
					ChunkStyles.argb(major ? 230 : 140, rgb));
			if (major) {
				s.line(x - 0.6, y + 0.6, z, x, y, z, 2.0F, ChunkStyles.argb(200, rgb));
				s.line(x + 0.6, y + 0.6, z, x, y, z, 2.0F, ChunkStyles.argb(200, rgb));
			}
		}
		double p = top - ((timeMs / 1400.0) % 1.0) * (top - bottom), w = 0.3;
		s.quad(x - w, p, z - w, x + w, p, z - w, x + w, p + 1.2, z + w,
				x - w, p + 1.2, z + w, ChunkStyles.argb(230, 0xFFFFFF));
	}

	/** HONEYCOMB (Hive): pointy-top hexagon tiles over the chunk, brightening in a wave that travels out from the center. */
	public static void honeycomb(DrawSink s, int cx, int cz, double y, int rgb, double timeMs) {
		double r = 2.0, w = Math.sqrt(3) * r, h = 1.5 * r, mx = cx * 16.0 + 8, mz = cz * 16.0 + 8;
		double x0 = cx * 16.0, z0 = cz * 16.0;
		for (int row = 0; row * h < 17; row++) {
			double cz2 = z0 + row * h;
			double offset = (row % 2 == 0) ? 0 : w / 2;
			for (int col = 0; col * w < 17; col++) {
				double hx = x0 + offset + col * w;
				if (hx > x0 + 16 || cz2 > z0 + 16) {
					continue;
				}
				double dist = Math.hypot(hx - mx, cz2 - mz), wave = 0.5 + 0.5 * Math.sin(timeMs / 500.0 - dist * 0.9);
				int a = (int) (70 + 160 * wave);
				for (int k = 0; k < 6; k++) {
					double a0 = Math.PI / 6 + Math.PI / 3 * k, a1 = a0 + Math.PI / 3;
					s.line(hx + Math.cos(a0) * r * 0.92, y, cz2 + Math.sin(a0) * r * 0.92,
							hx + Math.cos(a1) * r * 0.92, y, cz2 + Math.sin(a1) * r * 0.92,
							1.5F, ChunkStyles.argb(a, rgb));
				}
			}
		}
	}

	/** STRATUM SCAN (Seed Chunk Finder): a bright scan line sweeping across a faint diagonal hatch. */
	public static void stratumScan(DrawSink s, int cx, int cz, double y, int rgb, double timeMs) {
		double x0 = cx * 16.0, z0 = cz * 16.0, x1 = x0 + 16, z1 = z0 + 16;
		int faint = ChunkStyles.argb(45, rgb);
		for (int i = -16; i <= 16; i += 4) {
			double xa = Math.max(x0, x0 + i);
			double za = Math.max(z0, z0 - i);
			double xb = Math.min(x1, x1 + i);
			double zb = Math.min(z1, z1 - i);
			s.line(xa, y + 0.01, za, xb, y + 0.01, zb, 1.0F, faint);
		}
		double p = (timeMs / 1800.0) % 1.0;
		double scanZ = z0 + p * 16.0;
		s.line(x0, y + 0.06, scanZ, x1, y + 0.06, scanZ, 3.0F, ChunkStyles.argb(255, rgb));
		for (int i = 0; i < 8; i++) {
			// The trail is clipped at the edge instead of being shortened, so
			// every frame emits the same number of primitives.
			double back = Math.max(z0 + 0.25, scanZ - (i + 1) * 0.35);
			s.quad(x0, y + 0.04, back, x1, y + 0.04, back,
					x1, y + 0.04, back - 0.25, x0, y + 0.04, back - 0.25,
					ChunkStyles.argb(70 - i * 7, rgb));
		}
	}

	/** BLOOM (Artificial Light ESP, Light Finder): soft breathing rings around a bright core cross. */
	public static void bloom(DrawSink s, double x, double y, double z, int rgb, double timeMs) {
		for (int r = 0; r < 3; r++) {
			double phase = (timeMs / 1600.0 + r / 3.0) % 1.0;
			double radius = 0.45 + phase * 2.1;
			int alpha = (int) (150 * Math.pow(1.0 - phase, 1.4));
			ring(s, x, y + 0.03 + r * 0.01, z, radius, 28, 1.8F, ChunkStyles.argb(alpha, rgb));
			double inner = Math.max(0, radius - 0.18);
			for (int k = 0; k < 16; k++) {
				double a0 = Math.PI * 2 * k / 16, a1 = Math.PI * 2 * (k + 1) / 16;
				s.quad(x + Math.cos(a0) * inner, y + 0.02, z + Math.sin(a0) * inner,
						x + Math.cos(a0) * radius, y + 0.02, z + Math.sin(a0) * radius,
						x + Math.cos(a1) * radius, y + 0.02, z + Math.sin(a1) * radius,
						x + Math.cos(a1) * inner, y + 0.02, z + Math.sin(a1) * inner,
						ChunkStyles.argb(alpha / 4, rgb));
			}
		}
		s.line(x - 0.55, y + 0.08, z, x + 0.55, y + 0.08, z, 2.0F, ChunkStyles.argb(255, rgb));
		s.line(x, y + 0.08, z - 0.55, x, y + 0.08, z + 0.55, 2.0F, ChunkStyles.argb(255, rgb));
		s.line(x, y + 0.08, z, x, y + 1.1, z, 2.0F, ChunkStyles.argb(180, rgb));
	}

	/** VORTEX (Bedrock Void ESP): a rotating spiral that tightens toward the hole core. */
	public static void vortex(DrawSink s, double x, double y, double z, int rgb, double timeMs) {
		double spin = timeMs / 900.0;
		for (int arm = 0; arm < 2; arm++) {
			double previousX = x + Math.cos(spin + arm * Math.PI) * 3.0;
			double previousZ = z + Math.sin(spin + arm * Math.PI) * 3.0;
			for (int i = 1; i <= 36; i++) {
				double t = i / 36.0;
				double radius = 3.0 * (1.0 - t * 0.88);
				double angle = spin + arm * Math.PI + t * Math.PI * 3.0;
				double nextX = x + Math.cos(angle) * radius;
				double nextZ = z + Math.sin(angle) * radius;
				s.line(previousX, y + t * 0.08, previousZ, nextX, y + t * 0.08, nextZ,
						1.8F, ChunkStyles.argb((int) (210 * (1.0 - t * 0.4)), rgb));
				previousX = nextX;
				previousZ = nextZ;
			}
		}
		ring(s, x, y + 0.02, z, 3.0, 32, 1.6F, ChunkStyles.argb(130, rgb));
	}

	/** CORNER TICKS (Block Notifier): four brackets pulsing inward around a small beacon diamond. */
	public static void cornerTicks(DrawSink s, double x, double y, double z, double half, int rgb, double timeMs) {
		double pulse = 0.65 + 0.35 * (0.5 + 0.5 * Math.sin(timeMs / 420.0));
		double arm = half * 0.55 * pulse;
		int c = ChunkStyles.argb((int) (150 + 90 * pulse), rgb);
		double[][] corners = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
		for (double[] corner : corners) {
			double cx = x + corner[0] * half;
			double cz = z + corner[1] * half;
			s.line(cx, y, cz, cx - corner[0] * arm, y, cz, 2.2F, c);
			s.line(cx, y, cz, cx, y, cz - corner[1] * arm, 2.2F, c);
		}
		double d = 0.22;
		s.quad(x, y + 0.04, z - d, x + d, y + 0.04, z, x, y + 0.04, z + d, x - d, y + 0.04, z,
				ChunkStyles.argb(255, rgb));
		s.line(x, y + 0.04, z, x, y + 0.9, z, 1.6F, ChunkStyles.argb(170, rgb));
	}

	/** HELIX BEAM (Spawner Beam): two intertwined spirals climbing a vertical beam. */
	public static void helixBeam(DrawSink s, double x, double z, double yBottom, double yTop, int rgb, double timeMs) {
		double height = Math.max(0.1, yTop - yBottom);
		s.line(x, yBottom, z, x, yTop, z, 1.8F, ChunkStyles.argb(150, rgb));
		for (int arm = 0; arm < 2; arm++) {
			double previousX = x;
			double previousZ = z;
			for (int i = 0; i <= 42; i++) {
				double t = i / 42.0;
				double angle = timeMs / 700.0 + arm * Math.PI + t * Math.PI * 4.0;
				double radius = 0.55 * (1.0 - t * 0.35);
				double nextX = x + Math.cos(angle) * radius;
				double nextZ = z + Math.sin(angle) * radius;
				if (i > 0) {
					s.line(previousX, yBottom + t * height, previousZ, nextX, yBottom + t * height, nextZ,
							1.6F, ChunkStyles.argb(200, rgb));
				}
				previousX = nextX;
				previousZ = nextZ;
			}
		}
		ring(s, x, yTop, z, 0.7, 18, 1.4F, ChunkStyles.argb(220, rgb));
	}

	/** HEAT RING (Activity Inspector): thickness and brightness follow the activity score. */
	public static void heatRing(DrawSink s, double x, double y, double z, double radius, int rgb,
			double score, double timeMs) {
		double heat = Math.max(0.0, Math.min(1.0, score / 20.0));
		double pulse = 0.85 + 0.15 * Math.sin(timeMs / 380.0);
		float width = (float) (1.2 + heat * 2.4);
		ring(s, x, y + 0.03, z, radius * pulse, 32, width,
				ChunkStyles.argb((int) (110 + 130 * heat), rgb));
		ring(s, x, y + 0.04, z, radius * (0.55 + 0.2 * heat), 24, width * 0.7F,
				ChunkStyles.argb((int) (70 + 150 * heat), rgb));
		double d = 0.25 + heat * 0.35;
		s.quad(x - d, y + 0.05, z, x, y + 0.05, z + d, x + d, y + 0.05, z, x, y + 0.05, z - d,
				ChunkStyles.argb(220, rgb));
	}
}
