package com.griefer.client.module.modules;

import com.griefer.client.module.Category;
import com.griefer.client.module.Module;
import com.griefer.client.module.SliderSetting;

/**
 * Swing Speed — re-times the first-person arm swing animation so it looks like
 * Mining Fatigue slow-swinging (or faster), while actual attack/mining speed
 * is untouched.
 *
 * Purely visual: only the client-side hand animation changes. The animation is
 * driven by a mixin into {@code LivingEntity.updateSwingTime()}, which advances
 * {@code swingTime} and computes {@code attackAnim = swingTime / duration} —
 * exactly the value the first-person hand renderer samples.
 *
 * Speed mapping (hyperbolic, fatigue-like):
 *   speed 10 -> 6 ticks  (vanilla normal swing)
 *   speed  6 -> 10 ticks
 *   speed  1 -> 60 ticks (very slow, "digger fatigue" look)
 *
 * The value is clamped at every entry point (GUI drag, config load) so an
 * invalid value can never produce a broken or zero-length animation.
 */
public class SwingSpeedModule extends Module {
	/** Vanilla's default swing duration in ticks — what speed 10 must produce. */
	public static final int VANILLA_SWING_TICKS = 6;

	public static final int MIN_SPEED = 1;
	public static final int MAX_SPEED = 10;
	public static final int DEFAULT_SPEED = 6;

	private final SliderSetting speed = setting(new SliderSetting("speed", DEFAULT_SPEED, MIN_SPEED, MAX_SPEED));

	public SwingSpeedModule() {
		super(Category.BASE_FINDER, "swing_speed");
	}

	/**
	 * Swing animation duration in ticks while this module is enabled.
	 * Always clamped to a sane positive range.
	 */
	public int swingTicks() {
		return clampTicks((int) Math.round(60f / clampedSpeed()));
	}

	private int clampedSpeed() {
		return Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed.get()));
	}

	private static int clampTicks(int ticks) {
		return Math.max(1, Math.min(120, ticks));
	}

	/**
	 * Hardens the stored value after config loads or any external set, so an
	 * out-of-range saved value can never break the animation state.
	 */
	public void sanitize() {
		if (speed.get() < MIN_SPEED || speed.get() > MAX_SPEED) {
			speed.reset();
		}
	}

	public int speed() {
		return clampedSpeed();
	}
}
