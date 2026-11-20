package com.capy.client.finder.core;

/**
 * Small, pure rules shared by the finder modules.
 *
 * <p>They live here instead of inside a module so the behaviour a setting
 * promises can be checked without a running client: the plate height, the
 * shaft exclusion and the frame-rate independent decay are all decisions the
 * modules make from plain numbers.</p>
 */
public final class FinderRules {
	private FinderRules() {
	}

	/**
	 * The Y of a chunk plate.
	 *
	 * <p>Following the player is exactly the player's feet. Otherwise the plate
	 * hugs the sampled terrain, but never rises above {@code height}: that is
	 * the ceiling a player sets when a surface base sits under a mountain and
	 * the plate would otherwise be drawn above the build limit they care
	 * about.</p>
	 */
	public static double plateY(double terrainAverage, double playerY, boolean followPlayer, int height) {
		if (followPlayer) {
			return playerY + 0.05;
		}
		if (Double.isNaN(terrainAverage)) {
			return height + 0.05;
		}
		return Math.min(terrainAverage, height) + 0.05;
	}

	/**
	 * True when a sampled column contains a vertical run of air at least
	 * {@code minRun} blocks long. The finder samples from just under the
	 * surface downwards; a run that long is a shaft, not a cave pocket.
	 */
	public static boolean hasVerticalRun(boolean[] airFromSurfaceDown, int minRun) {
		if (airFromSurfaceDown == null || minRun <= 0) {
			return false;
		}
		int run = 0;
		for (boolean air : airFromSurfaceDown) {
			if (air) {
				run++;
				if (run >= minRun) {
					return true;
				}
			} else {
				run = 0;
			}
		}
		return false;
	}

	/**
	 * Hits inside a window, with the window measured from the newest hit.
	 * Timestamps are milliseconds; an older hit outside the window is ignored.
	 */
	public static int hitsInWindow(long[] hitTimes, long nowMs, long windowMs) {
		if (hitTimes == null || windowMs <= 0) {
			return 0;
		}
		int count = 0;
		for (long time : hitTimes) {
			if (nowMs - time <= windowMs) {
				count++;
			}
		}
		return count;
	}

	/** The Memory setting's half-life: half the full decay window. */
	public static double halfLifeForWindowSeconds(int seconds) {
		return Math.max(1, seconds) * 1000.0 / 2.0;
	}

	/** Colour ramp from the low colour to the high one, by 0..1 strength. */
	public static int ramp(int lowArgb, int highArgb, double strength) {
		double t = Math.max(0.0, Math.min(1.0, strength));
		int a = lerp((lowArgb >>> 24) & 0xFF, (highArgb >>> 24) & 0xFF, t);
		int r = lerp((lowArgb >>> 16) & 0xFF, (highArgb >>> 16) & 0xFF, t);
		int g = lerp((lowArgb >>> 8) & 0xFF, (highArgb >>> 8) & 0xFF, t);
		int b = lerp(lowArgb & 0xFF, highArgb & 0xFF, t);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	/** Sound Bypass' fade: full at the threshold, gone at twice the score. */
	public static double scoreFade(double score, double threshold) {
		if (threshold <= 0.0) {
			return 1.0;
		}
		return Math.max(0.0, Math.min(1.0, (score - threshold) / threshold));
	}

	/** True when a chunk centre is inside the ignore radius in blocks. */
	public static boolean withinBlocks(int chunkX, int chunkZ, double playerX, double playerZ, int radius) {
		if (radius <= 0) {
			return false;
		}
		double dx = (chunkX * 16.0 + 8.0) - playerX;
		double dz = (chunkZ * 16.0 + 8.0) - playerZ;
		return dx * dx + dz * dz <= (double) radius * radius;
	}

	/** True when a target is moving closer to the observer. */
	public static boolean approaching(double previousDistance, double currentDistance, double speed) {
		return speed >= 0.4 && currentDistance < previousDistance;
	}

	private static int lerp(int from, int to, double t) {
		return (int) Math.round(from + (to - from) * t);
	}
}
