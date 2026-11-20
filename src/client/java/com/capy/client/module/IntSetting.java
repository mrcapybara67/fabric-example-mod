package com.capy.client.module;

/**
 * An integer setting with a range and a step.
 *
 * <p>Stored and drawn like a slider, but every write lands on a multiple of
 * {@code step} measured from {@code min}: a step of 5 with min 5 accepts 5, 10,
 * 15 ... and a value of 43 typed into a hand-edited config becomes 45. The
 * rounding lives in {@link #set}, so the module logic, the drag maths and the
 * config reader all see the same snapped value.</p>
 */
public class IntSetting extends SliderSetting {
	private final int step;

	public IntSetting(String key, int defaultValue, int min, int max, int step) {
		super(key, defaultValue, min, max);
		this.step = Math.max(1, Math.min(Math.max(1, max - min), step));
		// Snap the constructor value too: a default that is not a multiple of
		// the step would otherwise sit between two stops until the user moved it.
		set(defaultValue);
	}

	/** How far one click or one drag stop moves the value. */
	public int step() {
		return step;
	}

	@Override
	public void set(Integer value) {
		if (value == null) {
			super.set(defaultValue());
			return;
		}
		super.set(snap(value));
	}

	/** The nearest reachable value, clamped into the range. */
	public int snap(int raw) {
		int clamped = Math.max(min(), Math.min(max(), raw));
		int offset = clamped - min();
		int multiple = Math.round(offset / (float) step);
		return Math.max(min(), Math.min(max(), min() + multiple * step));
	}

	@Override
	public void reset() {
		set(defaultValue());
	}
}
