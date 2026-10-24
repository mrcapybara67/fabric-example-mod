package com.capy.client.module;

/**
 * A colour setting, stored as one packed ARGB int.
 *
 * The GUI edits it as hue / saturation / brightness plus an alpha, so the value
 * is exposed that way as well: {@link #hsv()} reads the current colour back as
 * HSV and {@link #setHsv} writes one without touching the alpha. Keeping the
 * conversion here (instead of in the picker) means the module always sees the
 * exact colour that was previewed, and the conversion can be tested on its own.
 *
 * Alpha is part of the value on purpose: a module can be made subtler without
 * losing its hue, which is what a "fill opacity" slider would otherwise do with
 * a second setting.
 */
public class ColorSetting extends Setting<Integer> {
	public ColorSetting(String key, int defaultArgb) {
		super(key, defaultArgb);
	}

	@Override
	public void set(Integer value) {
		super.set(value == null ? defaultValue() : value);
	}

	/** The colour as packed ARGB. Never null. */
	public int argb() {
		Integer value = get();
		return value == null ? defaultValue() : value;
	}

	public int alpha() {
		return (argb() >>> 24) & 0xFF;
	}

	public int red() {
		return (argb() >>> 16) & 0xFF;
	}

	public int green() {
		return (argb() >>> 8) & 0xFF;
	}

	public int blue() {
		return argb() & 0xFF;
	}

	/** The colour without alpha, as {@code RRGGBB}. */
	public int rgb() {
		return argb() & 0xFFFFFF;
	}

	/** {@code #RRGGBB}, the form shown next to the swatch. */
	public String hex() {
		return String.format("#%06X", rgb());
	}

	// ----------------------------------------------------------- conversions

	/**
	 * The current colour as hue (0..360), saturation (0..1) and brightness
	 * (0..1), in the order the picker draws its tracks.
	 */
	public float[] hsv() {
		return toHsv(rgb());
	}

	/** Writes a colour from HSV, keeping the current alpha. */
	public void setHsv(float hue, float saturation, float brightness) {
		set(toArgb(hue, saturation, brightness, alpha()));
	}

	/** Replaces the alpha channel, keeping hue and brightness. */
	public void setAlpha(int alpha) {
		int a = clamp8(alpha);
		set((a << 24) | rgb());
	}

	/** RGB to HSV. Grey colours report a hue of 0 rather than NaN. */
	public static float[] toHsv(int rgb) {
		float r = ((rgb >>> 16) & 0xFF) / 255f;
		float g = ((rgb >>> 8) & 0xFF) / 255f;
		float b = (rgb & 0xFF) / 255f;
		float max = Math.max(r, Math.max(g, b));
		float min = Math.min(r, Math.min(g, b));
		float delta = max - min;
		float hue;
		if (delta <= 1.0e-6f) {
			hue = 0f;
		} else if (max == r) {
			hue = 60f * (((g - b) / delta) % 6f);
		} else if (max == g) {
			hue = 60f * (((b - r) / delta) + 2f);
		} else {
			hue = 60f * (((r - g) / delta) + 4f);
		}
		if (hue < 0f) {
			hue += 360f;
		}
		float saturation = max <= 0f ? 0f : delta / max;
		return new float[] {hue, saturation, max};
	}

	/** HSV to packed ARGB. The hue wraps, so a drag past the end stays sane. */
	public static int toArgb(float hue, float saturation, float brightness, int alpha) {
		float h = ((hue % 360f) + 360f) % 360f;
		float s = clamp01(saturation);
		float v = clamp01(brightness);
		float c = v * s;
		float x = c * (1f - Math.abs((h / 60f) % 2f - 1f));
		float m = v - c;
		float r;
		float g;
		float b;
		if (h < 60f) {
			r = c;
			g = x;
			b = 0f;
		} else if (h < 120f) {
			r = x;
			g = c;
			b = 0f;
		} else if (h < 180f) {
			r = 0f;
			g = c;
			b = x;
		} else if (h < 240f) {
			r = 0f;
			g = x;
			b = c;
		} else if (h < 300f) {
			r = x;
			g = 0f;
			b = c;
		} else {
			r = c;
			g = 0f;
			b = x;
		}
		return (clamp8(alpha) << 24)
				| (clamp8(Math.round((r + m) * 255f)) << 16)
				| (clamp8(Math.round((g + m) * 255f)) << 8)
				| clamp8(Math.round((b + m) * 255f));
	}

	private static float clamp01(float value) {
		return Math.max(0f, Math.min(1f, value));
	}

	private static int clamp8(int value) {
		return Math.max(0, Math.min(255, value));
	}
}
