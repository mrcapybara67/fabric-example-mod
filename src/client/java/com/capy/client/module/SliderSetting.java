package com.capy.client.module;

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

	/**
	 * Sets the value, clamped into the slider's range. A stored value that is
	 * out of range (a hand edited config, for example) can therefore never
	 * reach the module logic.
	 */
	@Override
	public void set(Integer value) {
		if (value == null) {
			super.set(defaultValue());
			return;
		}
		super.set(Math.max(min, Math.min(max, value)));
	}

	/** Sets the value from a 0..1 fraction (used when dragging the slider). */
	public void setFromFraction(double fraction) {
		double f = Math.max(0.0, Math.min(1.0, fraction));
		set((int) Math.round(min + f * (max - min)));
	}

	/**
	 * Current value as a 0..1 fraction (used to draw the slider fill).
	 *
	 * A slider with no range is answered as "empty" instead of dividing by
	 * zero: NaN would reach the drawing layer and the knob would disappear.
	 */
	public double fraction() {
		int span = max - min;
		if (span <= 0) {
			return 0.0;
		}
		return Math.max(0.0, Math.min(1.0, (value - min) / (double) span));
	}
}
