package com.capy.client.module.modules;

/**
 * Small, testable helpers shared by the atmosphere state and renderers.
 *
 * <p>Time of day is a circle: 0 and 1 are the same midnight. Easing it in a
 * straight line makes dusk at 0.8 pass through noon on the way to 0.0, which
 * flashes the sky to daylight for a moment. The eased value therefore follows
 * the shorter of the two arcs around the circle.</p>
 */
public final class AtmosphereMath {
	private AtmosphereMath() {
	}

	/**
	 * Eases an optional time override towards its target.
	 *
	 * <p>A target below zero is "use the server's time" and snaps, so the hook
	 * never reads a negative fraction as a legal time. Enabling from off also
	 * snaps to the requested time instead of rising out of midnight.</p>
	 */
	public static float easeTimeOfDay(float current, float target, float step) {
		if (target < 0f) {
			return -1f;
		}
		if (current < 0f) {
			return wrap(target);
		}
		return wrap(current + shortestDelta(current, target) * clamp01(step));
	}

	/** The signed shortest distance from {@code from} to {@code to}, in (-0.5, 0.5]. */
	public static float shortestDelta(float from, float to) {
		float delta = wrap(to) - wrap(from);
		if (delta > 0.5f) {
			delta -= 1f;
		} else if (delta < -0.5f) {
			delta += 1f;
		}
		return delta;
	}

	/** Wraps a fraction into [0, 1). */
	public static float wrap(float value) {
		float wrapped = value % 1f;
		return wrapped < 0f ? wrapped + 1f : wrapped;
	}

	/**
	 * Moon terminator shading for one point on the disc.
	 *
	 * <p>{@code dx} is the horizontal position across the visible disc,
	 * normalised to -1 on the left rim and +1 on the right. The phase runs
	 * from 0 (full) through 0.5 (new) and back towards 1 (full). The
	 * terminator is the phase's cosine, with a narrow smooth edge so a
	 * crescent has a soft, curved boundary rather than a hard line.</p>
	 *
	 * @return 1 for the lit side, 0 for the shadowed side
	 */
	public static float moonLight(float dx, float phase) {
		float x = Math.max(-1f, Math.min(1f, dx));
		float terminator = (float) Math.cos(wrap(phase) * Math.PI * 2.0);
		float edge = 0.10f;
		float t = smoothstep(terminator - edge, terminator + edge, x);
		return 1f - t;
	}

	/**
	 * A neon grid line's world coordinate. Lines sit on exact multiples of the
	 * spacing; the camera position is subtracted only when the vertex is
	 * emitted, never stored into the grid itself.
	 */
	public static double neonLineCoordinate(int index, double spacing) {
		return index * spacing;
	}

	/** The only conversion a world-space vertex needs before it is submitted. */
	public static double cameraRelative(double world, double camera) {
		return world - camera;
	}

	/** Linear position interpolation used by the detached camera. */
	public static double interpolate(double previous, double current, float partialTick) {
		float t = clamp01(partialTick);
		return previous + (current - previous) * t;
	}

	/** One step of the freecam inertia model. */
	public static double inertia(double velocity, double target, double inertia) {
		double kept = Math.max(0.0, Math.min(0.9, inertia));
		return velocity * kept + target * (1.0 - kept);
	}

	/**
	 * The refresh rule for the neon height cache: ten game ticks, or any chunk
	 * change, whichever happens first.
	 */
	public static boolean heightCacheNeedsRefresh(long nowTick, long lastRefreshTick,
			boolean chunkChanged) {
		return chunkChanged || lastRefreshTick < 0L || nowTick - lastRefreshTick >= 10L;
	}

	private static float smoothstep(float edge0, float edge1, float value) {
		if (edge1 <= edge0) {
			return value < edge0 ? 0f : 1f;
		}
		float t = Math.max(0f, Math.min(1f, (value - edge0) / (edge1 - edge0)));
		return t * t * (3f - 2f * t);
	}

	private static float clamp01(float value) {
		return Math.max(0f, Math.min(1f, value));
	}
}
