package com.griefer.client.module.modules;

import com.griefer.client.module.Category;
import com.griefer.client.module.Module;
import com.griefer.client.module.SliderSetting;

/**
 * Swing Speed — re-times the arm swing animation so it looks like Mining Fatigue
 * slow-swinging (or faster), while actual attack/mining speed is untouched.
 *
 * Purely visual: only the client-side animation duration changes.
 *
 * Speed mapping (hyperbolic, fatigue-like):
 *   speed 10 -> 6 ticks (vanilla normal swing)
 *   speed  1 -> 60 ticks (very slow, "digger fatigue" look)
 */
public class SwingSpeedModule extends Module {
	private final SliderSetting speed = setting(new SliderSetting("speed", 6, 1, 10));

	public SwingSpeedModule() {
		super(Category.BASE_FINDER, "swing_speed");
	}

	/** Swing animation duration in ticks while this module is enabled. */
	public int swingTicks() {
		return Math.max(1, Math.round(60f / speed.get()));
	}

	public int speed() {
		return speed.get();
	}
}
