package com.capy.client.module;

import java.util.Locale;

/**
 * Integer slider setting, clamped between min and max.
 * Used both by module logic and rendered directly by the ClickGUI.
 *
 * Fractional ranges (0.0-3.0 in steps of 0.1, 0.05-0.95, and so on) are stored
 * as fixed point: the value is kept as an int and {@link #decimals()} says how
 * many of its digits are behind the point. {@code speed = 10} with one decimal
 * is 1.0, {@code opacity = 85} with two is 0.85. Keeping the storage integral
 * means the drag maths, the clamping and the config file stay exactly as they
 * were; only the label the player reads is scaled.
 */
public class SliderSetting extends Setting<Integer> {
	private final int min;
	private final int max;
	private final int decimals;

	public SliderSetting(String key, int defaultValue, int min, int max) {
		this(key, defaultValue, min, max, 0);
	}

	/**
	 * @param decimals digits shown after the point; the value is therefore in
	 *                 units of {@code 10^-decimals} (2 turns 85 into 0.85)
	 */
	public SliderSetting(String key, int defaultValue, int min, int max, int decimals) {
		super(key, defaultValue);
		this.min = min;
		this.max = max;
		this.decimals = Math.max(0, Math.min(3, decimals));
	}

	public int min() {
		return min;
	}

	public int max() {
		return max;
	}

	public int decimals() {
		return decimals;
	}

	/** The value the player sees, with the fixed point applied. */
	public double real() {
		return get() / (double) scale();
	}

	/** How many stored units make one whole number. */
	private int scale() {
		int scale = 1;
		for (int i = 0; i < decimals; i++) {
			scale *= 10;
		}
		return scale;
	}

	/** The label shown on the right hand side of the slider. */
	public String display() {
		if (decimals == 0) {
			return String.valueOf(get());
		}
		return String.format(Locale.ROOT, "%." + decimals + "f", real());
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
