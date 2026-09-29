package com.griefer.client.module;

/**
 * Integer slider setting, clamped between min and max.
 * Used both by module logic and rendered directly by the ClickGUI.
 */
public class SliderSetting extends Setting<Integer> {
	private final int min;
	private final int max;

	public SliderSetting(String key, int defaultValue, int min, int max) {
		super(key, defaultValue);
		this.min = min;
		this.max = max;
	}

	public int min() {
		return min;
	}

	public int max() {
		return max;
	}

	/** Sets the value from a 0..1 fraction (used when dragging the slider). */
	public void setFromFraction(double fraction) {
		double f = Math.max(0.0, Math.min(1.0, fraction));
		set((int) Math.round(min + f * (max - min)));
	}

	/** Current value as a 0..1 fraction (used to draw the slider fill). */
	public double fraction() {
		return (value - min) / (double) (max - min);
	}
}
