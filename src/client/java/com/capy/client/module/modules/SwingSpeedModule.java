package com.capy.client.module.modules;

import com.capy.client.module.Category;
import com.capy.client.module.Configurable;
import com.capy.client.module.Module;
import com.capy.client.module.SliderSetting;

/**
 * Swing Speed — re-times the first-person arm swing animation, faster or
 * slower, while the actual attack and mining speed stay untouched.
 *
 * Purely visual: only the client-side hand animation changes. The animation is
 * driven by a mixin into {@code LivingEntity.updateSwingTime()}, which advances
 * {@code swingTime} and computes {@code attackAnim = swingTime / duration} —
 * exactly the value the first-person hand renderer samples.
 *
 * The slider is centred on vanilla:
 *   speed 20 -> 3 ticks  (twice as fast)
 *   speed 10 -> 6 ticks  (vanilla, the default)
 *   speed  5 -> 12 ticks
 *   speed  1 -> 60 ticks (very slow, "mining fatigue" look)
 *
 * 10 is deliberately the default and deliberately vanilla: the module does
 * nothing until the slider is moved. The previous build mapped 10 to vanilla
 * as well but defaulted to 6 and stopped at 10, so the only possible change
 * was slower than vanilla — moving the slider to the right appeared to do
 * nothing, which is exactly what "swing speed is not working" looks like.
 *
 * The value is clamped at every entry point (GUI drag, config load) so an
 * invalid value can never produce a broken or zero-length animation.
 */
public class SwingSpeedModule extends Module implements Configurable {
	/** The slider value that reproduces vanilla's own swing. */
	public static final int VANILLA_SPEED = 10;

	public static final int MIN_SPEED = 1;
	public static final int MAX_SPEED = 20;
	public static final int DEFAULT_SPEED = VANILLA_SPEED;

	private final SliderSetting speed = setting(new SliderSetting("speed", DEFAULT_SPEED, MIN_SPEED, MAX_SPEED));

	public SwingSpeedModule() {
		super(Category.VISUAL, "swing_speed");
	}

	/**
	 * Scales the swing duration vanilla computed for the held item.
	 *
	 * @param vanillaDuration the duration in ticks vanilla wants to use
	 * @return the scaled duration, never below one tick
	 */
	public int scaleSwingDuration(int vanillaDuration) {
		int base = Math.max(1, vanillaDuration);
		float factor = VANILLA_SPEED / (float) clampedSpeed();
		return clampTicks(Math.round(base * factor));
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

	// ---------------------------------------------------------- persistence

	@Override
	public void loadConfig(com.google.gson.JsonObject json) {
		int stored = com.capy.client.config.ConfigValues.intValue(json, "speed", DEFAULT_SPEED);
		speed.set(stored < MIN_SPEED || stored > MAX_SPEED ? DEFAULT_SPEED : stored);
	}

	@Override
	public void saveConfig(com.google.gson.JsonObject json) {
		json.addProperty("speed", clampedSpeed());
	}
}
