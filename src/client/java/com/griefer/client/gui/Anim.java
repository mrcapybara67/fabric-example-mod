package com.griefer.client.gui;

/**
 * Tiny animation state: eases a value toward a target each frame.
 *
 * Used for hover states, toggle transitions, sidebar selection and window
 * open/close. Purely per-frame math — no tick listeners, nothing persists
 * after the GUI closes (every instance dies with the screen).
 */
final class Anim {
	private float value;
	private final float speedPerFrame;

	Anim(float initial, float speedPerFrame) {
		this.value = initial;
		this.speedPerFrame = speedPerFrame;
	}

	void to(float target, float deltaTicks) {
		float steps = Math.max(0.0f, Math.min(4.0f, deltaTicks));
		float t = 1.0f - (float) Math.exp(-speedPerFrame * steps);
		value += (target - value) * t;
		if (Math.abs(value - target) < 0.002f) {
			value = target;
		}
	}

	float value() {
		return value;
	}

	boolean settled() {
		return value <= 0.001f || value >= 0.999f;
	}
}
