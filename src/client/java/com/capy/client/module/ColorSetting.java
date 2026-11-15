package com.capy.client.module;

import com.capy.client.config.ConfigValues;
import com.capy.client.finder.core.ColorMath;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

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
	private static final int RECENT_MAX = 8;
	private ColorMath.Mode mode = ColorMath.Mode.STATIC;
	private int second = 0xFFFFFFFF;
	private double speed = 1.0;
	private final int[] recent = new int[RECENT_MAX];
	private int recentCount;

	public ColorSetting(String key, int defaultArgb) {
		super(key, defaultArgb);
	}

	public ColorMath.Mode mode() {
		return mode;
	}

	public void setMode(ColorMath.Mode mode) {
		this.mode = mode == null ? ColorMath.Mode.STATIC : mode;
	}

	public int second() {
		return second;
	}

	public void setSecond(int second) {
		this.second = second;
	}

	public double speed() {
		return speed;
	}

	public void setSpeed(double speed) {
		this.speed = Math.max(0.25, Math.min(4.0, speed));
	}

	public int recentCount() {
		return recentCount;
	}

	public int recentAt(int index) {
		return index >= 0 && index < recentCount ? recent[index] : 0;
	}

	/** Adds a colour to the bounded recent list, moving a duplicate to the front. */
	public void remember(int argb) {
		for (int i = 0; i < recentCount; i++) {
			if (recent[i] == argb) {
				System.arraycopy(recent, 0, recent, 1, i);
				recent[0] = argb;
				return;
			}
		}
		int keep = Math.min(recentCount, RECENT_MAX - 1);
		System.arraycopy(recent, 0, recent, 1, keep);
		recent[0] = argb;
		recentCount = Math.min(RECENT_MAX, recentCount + 1);
	}

	/** The visible colour at a wall-clock time, including the animated modes. */
	public int argbAt(double timeSec) {
		return ColorMath.eval(mode, argb(), second, timeSec, speed);
	}

	/** Loads the base integer plus the optional animation keys. */
	public void load(JsonObject json, String prefix) {
		set(ConfigValues.intValue(json, prefix, argb()));
		String storedMode = ConfigValues.stringValue(json, prefix + "_mode", mode.name());
		try {
			setMode(ColorMath.Mode.valueOf(storedMode));
		} catch (IllegalArgumentException ignored) {
			setMode(ColorMath.Mode.STATIC);
		}
		setSecond(ConfigValues.intValue(json, prefix + "_second", 0xFFFFFFFF));
		setSpeed(ConfigValues.intValue(json, prefix + "_speed", 100) / 100.0);
		recentCount = 0;
		JsonElement recentElement = json == null ? null : json.get(prefix + "_recent");
		if (recentElement != null && recentElement.isJsonArray()) {
			for (JsonElement item : recentElement.getAsJsonArray()) {
				if (recentCount >= RECENT_MAX || item == null || !item.isJsonPrimitive()) {
					break;
				}
				try {
					recent[recentCount++] = item.getAsInt();
				} catch (RuntimeException ignored) {
					break;
				}
			}
		}
	}

	/** Writes the base integer and every animation key. */
	public void save(JsonObject json, String prefix) {
		json.addProperty(prefix, argb());
		json.addProperty(prefix + "_mode", mode.name());
		json.addProperty(prefix + "_second", second);
		json.addProperty(prefix + "_speed", Math.round(speed * 100.0));
		JsonArray recentJson = new JsonArray();
		for (int i = 0; i < recentCount; i++) {
			recentJson.add(recent[i]);
		}
		json.add(prefix + "_recent", recentJson);
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
		return ColorMath.hex(argb(), false);
	}

	// ----------------------------------------------------------- conversions

	/**
	 * The current colour as hue (0..360), saturation (0..1) and brightness
	 * (0..1), in the order the picker draws its tracks.
	 */
	public float[] hsv() {
		float[] out = new float[3];
		hsvInto(out);
		return out;
	}

	/** Writes the current HSV into an existing array, avoiding a per-frame allocation. */
	public void hsvInto(float[] out) {
		toHsv(rgb(), out);
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
		float[] out = new float[3];
		toHsv(rgb, out);
		return out;
	}

	/** Same conversion, writing into a reusable array of length at least three. */
	public static void toHsv(int rgb, float[] out) {
		float[] hsv = ColorMath.hsv(rgb);
		out[0] = hsv[0];
		out[1] = hsv[1];
		out[2] = hsv[2];
	}

	/** HSV to packed ARGB. The hue wraps, so a drag past the end stays sane. */
	public static int toArgb(float hue, float saturation, float brightness, int alpha) {
		return ColorMath.fromHsv(hue, saturation, brightness, alpha);
	}

	private static int clamp8(int value) {
		return Math.max(0, Math.min(255, value));
	}
}
