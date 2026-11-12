package com.capy.client.gui;

import com.capy.client.config.ClientConfig;


/**
 * A single animated value.
 *
 * The animation is frame rate independent: the delta is converted from ticks
 * to seconds, so a value converges at the same rate at 30 and at 240 frames
 * per second. The speed is expressed as "how many e-folds per second", so a
 * speed of 8 settles in roughly a quarter of a second.
 */
public class Anim {
	private static long updates;
	private float value;
	private float target;
	private float speed;

	public Anim(float value, float speed) {
		this.value = value;
		this.target = value;
		this.speed = speed;
	}

	/**
	 * Moves the value towards the target.
	 *
	 * @param delta the frame delta in ticks (1.0 is one twentieth of a second)
	 */
	public void to(float target, float delta) {
		this.target = target;
		if (Math.abs(value - target) <= 0.0001f) {
			value = target;
			return;
		}
		if (ClientConfig.get().reduceAnimations()) {
			this.value = target;
			updates++;
			return;
		}
		this.value = Motion.approach(this.value, target, speed, delta / 20f);
		updates++;
	}

	/** Number of values that actually moved; used by the static-screen test. */
	public static long updateCount() {
		return updates;
	}

	public static void resetUpdateCount() {
		updates = 0L;
	}

	/** Jumps straight to a value, cancelling the animation. */
	public void snap(float value) {
		this.value = value;
		this.target = value;
	}

	public float value() {
		return value;
	}

	public float target() {
		return target;
	}

	/** True while the value is still moving. */
	public boolean isMoving() {
		return Math.abs(target - value) > 0.001f;
	}
}
