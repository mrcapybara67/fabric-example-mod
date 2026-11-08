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

	private static float clamp01(float value) {
		return Math.max(0f, Math.min(1f, value));
	}
}
