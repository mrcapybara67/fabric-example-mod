package com.capy.client.gui;

/**
 * Easing functions.
 *
 * Every animation in the client uses one of these so the whole interface
 * moves with the same character: quick and decisive when something responds
 * to a click, slow and soft when something drifts.
 */
public final class Motion {
	private Motion() {
	}


	/** Fast start, gentle end - the default for entrances. */
	public static float easeOutCubic(float t) {
		t = clamp01(t);
		float inv = 1f - t;
		return 1f - inv * inv * inv;
	}

	/** Very soft end, good for large movements. */
	public static float easeOutQuint(float t) {
		t = clamp01(t);
		float inv = 1f - t;
		return 1f - inv * inv * inv * inv * inv;
	}

	/** Slow start, slow end - used for content that moves between pages. */
	public static float easeInOutCubic(float t) {
		t = clamp01(t);
		if (t < 0.5f) {
			return 4f * t * t * t;
		}
		float f = -2f * t + 2f;
		return 1f - (f * f * f) / 2f;
	}

	/** A small overshoot, used for the portrait that pops into place. */
	public static float easeOutBack(float t, float overshoot) {
		t = clamp01(t);
		float c1 = overshoot;
		float c3 = c1 + 1f;
		float inv = t - 1f;
		return 1f + c3 * inv * inv * inv + c1 * inv * inv;
	}


	/** Frame rate independent exponential approach. */
	public static float approach(float current, float target, float speed, float deltaSeconds) {
		float factor = 1f - (float) Math.exp(-speed * Math.max(0f, deltaSeconds));
		return current + (target - current) * factor;
	}

	/** Clamps a value into the 0..1 range. */
	public static float clamp01(float value) {
		return value < 0f ? 0f : (value > 1f ? 1f : value);
	}

}
