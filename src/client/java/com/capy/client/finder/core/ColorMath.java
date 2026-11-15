package com.capy.client.finder.core;

/** Pure colour maths shared by the picker, the settings and the renderers. */
public final class ColorMath {
	private ColorMath() {
	}

	public enum Mode {
		STATIC, RAINBOW, PULSE, GRADIENT
	}

	public static int argb(int a, int r, int g, int b) {
		return (clamp(a) << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
	}

	private static int clamp(int v) {
		return Math.max(0, Math.min(255, v));
	}

	/** Returns {hue 0..360, saturation 0..1, value 0..1}. */
	public static float[] hsv(int rgb) {
		float[] out = new float[3];
		hsvInto(rgb, out);
		return out;
	}

	/** Allocation-free HSV read into a reusable three element array. */
	public static void hsvInto(int rgb, float[] out) {
		float r = (rgb >> 16 & 255) / 255f;
		float g = (rgb >> 8 & 255) / 255f;
		float b = (rgb & 255) / 255f;
		float max = Math.max(r, Math.max(g, b));
		float min = Math.min(r, Math.min(g, b));
		float d = max - min;
		float h;
		if (d == 0) {
			h = 0;
		} else if (max == r) {
			h = 60f * (((g - b) / d) % 6f);
		} else if (max == g) {
			h = 60f * ((b - r) / d + 2f);
		} else {
			h = 60f * ((r - g) / d + 4f);
		}
		if (h < 0) {
			h += 360f;
		}
		out[0] = h;
		out[1] = max == 0 ? 0 : d / max;
		out[2] = max;
	}

	public static int fromHsv(float hue, float sat, float val, int alpha) {
		float h = ((hue % 360f) + 360f) % 360f / 60f;
		float c = val * sat;
		float x = c * (1f - Math.abs(h % 2f - 1f));
		float m = val - c;
		float r;
		float g;
		float b;
		switch ((int) h) {
			case 0 -> {
				r = c;
				g = x;
				b = 0;
			}
			case 1 -> {
				r = x;
				g = c;
				b = 0;
			}
			case 2 -> {
				r = 0;
				g = c;
				b = x;
			}
			case 3 -> {
				r = 0;
				g = x;
				b = c;
			}
			case 4 -> {
				r = x;
				g = 0;
				b = c;
			}
			default -> {
				r = c;
				g = 0;
				b = x;
			}
		}
		return argb(alpha, Math.round((r + m) * 255f), Math.round((g + m) * 255f),
				Math.round((b + m) * 255f));
	}

	public static String hex(int argb, boolean withAlpha) {
		return withAlpha ? String.format("#%08X", argb) : String.format("#%06X", argb & 0xFFFFFF);
	}

	/** True when {@link #parseHex} would accept the text. */
	public static boolean validHex(String text) {
		if (text == null) {
			return false;
		}
		String t = text.trim();
		if (t.startsWith("#")) {
			t = t.substring(1);
		}
		if (t.length() != 3 && t.length() != 6 && t.length() != 8) {
			return false;
		}
		try {
			Long.parseLong(t, 16);
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	/** Accepts RGB, RRGGBB and AARRGGBB with or without a leading #. */
	public static int parseHex(String text, int fallback) {
		if (text == null) {
			return fallback;
		}
		String t = text.trim();
		if (t.startsWith("#")) {
			t = t.substring(1);
		}
		try {
			switch (t.length()) {
				case 3 -> {
					int v = Integer.parseInt(t, 16);
					int r = v >> 8 & 15;
					int g = v >> 4 & 15;
					int b = v & 15;
					return argb(fallback >>> 24, r * 17, g * 17, b * 17);
				}
				case 6 -> {
					return (fallback & 0xFF000000) | Integer.parseInt(t, 16);
				}
				case 8 -> {
					return (int) Long.parseLong(t, 16);
				}
				default -> {
					return fallback;
				}
			}
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	public static int lerp(int a, int b, double t) {
		t = Math.max(0, Math.min(1, t));
		return argb((int) Math.round((a >>> 24) + ((b >>> 24) - (a >>> 24)) * t),
				(int) Math.round((a >> 16 & 255) + ((b >> 16 & 255) - (a >> 16 & 255)) * t),
				(int) Math.round((a >> 8 & 255) + ((b >> 8 & 255) - (a >> 8 & 255)) * t),
				(int) Math.round((a & 255) + ((b & 255) - (a & 255)) * t));
	}

	/** RAINBOW cycles hue, PULSE breathes alpha, GRADIENT blends base and second. */
	public static int eval(Mode mode, int base, int second, double timeSec, double speed) {
		double t = timeSec * Math.max(0.05, speed);
		return switch (mode) {
			case STATIC -> base;
			case RAINBOW -> {
				float[] hsv = hsv(base);
				yield fromHsv((float) (t * 60.0), Math.max(0.65f, hsv[1]),
						Math.max(0.75f, hsv[2]), base >>> 24);
			}
			case PULSE -> {
				double k = 0.775 + 0.225 * Math.sin(t * 2.2);
				yield (base & 0xFFFFFF) | ((int) Math.round((base >>> 24) * k) << 24);
			}
			case GRADIENT -> lerp(base, second, 0.5 + 0.5 * Math.sin(t * 1.4));
		};
	}

	/** Picker square: x = saturation 0..1, y = value 1..0. Row-major ARGB. */
	public static int[] svSquare(float hue, int size) {
		int[] px = new int[size * size];
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				px[y * size + x] = fromHsv(hue, x / (float) (size - 1),
						1f - y / (float) (size - 1), 255);
			}
		}
		return px;
	}
}
