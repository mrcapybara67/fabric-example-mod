package com.capy.client.gui;

/**
 * Shared drawing primitives. Every component renders through this layer so
 * corner radii, surface layering, shadows, clipping, icons and text ellipsis
 * behave identically everywhere.
 *
 * There is no shader and no blur: the whole GUI is built from plain fills
 * because that is all a flat canvas offers. The premium look comes from
 * disciplined layering instead:
 *
 *   1. a soft, layered shadow so a panel floats above the game;
 *   2. an opaque plum body whose colour is interpolated row by row from a
 *      slightly lighter top to a slightly darker bottom;
 *   3. a hairline border and a one pixel inner highlight along the top edge,
 *      which is what gives a surface its "sheet of tinted glass" thickness;
 *   4. a single accent hairline where a surface needs to draw the eye.
 */
public final class Ui {
	private Ui() {
	}

	// ---------------------------------------------------------------- shapes

	/** Filled rounded rectangle with true per-pixel corners. */
	public static void roundRect(UiCanvas c, int x, int y, int w, int h, int radius, int color) {
		if (w <= 0 || h <= 0) {
			return;
		}
		int r = clampRadius(radius, w, h);
		if (r <= 0) {
			c.fill(x, y, x + w, y + h, color);
			return;
		}
		c.fill(x + r, y, x + w - r, y + h, color);
		c.fill(x, y + r, x + r, y + h - r, color);
		c.fill(x + w - r, y + r, x + w, y + h - r, color);
		for (int i = 0; i < r; i++) {
			int inset = cornerInset(r, i);
			c.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
			c.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, color);
		}
	}

	/** Filled circle. */
	public static void circle(UiCanvas c, int cx, int cy, int radius, int color) {
		if (radius <= 0) {
			return;
		}
		for (int dy = -radius; dy <= radius; dy++) {
			int dx = (int) Math.sqrt((double) radius * radius - (double) dy * dy);
			c.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
		}
	}

	/**
	 * A ring that can be drawn partially, used for the ring that draws itself
	 * into place while the alert portrait appears.
	 *
	 * @param progress 0 draws nothing, 1 draws the complete ring
	 */
	public static void ring(UiCanvas c, int cx, int cy, int radius, int thickness, int color, float progress) {
		if (radius <= 0 || thickness <= 0 || progress <= 0f) {
			return;
		}
		float p = Math.min(1f, progress);
		int outer = radius + thickness / 2;
		int inner = radius - thickness / 2;
		if (inner < 0) {
			inner = 0;
		}
		int total = Math.max(1, (int) (Math.PI * 2 * radius));
		int drawn = (int) (total * p);
		for (int i = 0; i < drawn; i++) {
			double angle = -Math.PI / 2 + (i / (double) total) * Math.PI * 2;
			int px = cx + (int) Math.round(Math.cos(angle) * radius);
			int py = cy + (int) Math.round(Math.sin(angle) * radius);
			c.fill(px - thickness / 2, py - thickness / 2,
					px - thickness / 2 + thickness, py - thickness / 2 + thickness, color);
		}
		if (inner >= 0 && p >= 1f) {
			// Nothing to do; the ring is complete.
			return;
		}
	}

	/**
	 * The horizontal inset of a rounded corner at row {@code i} measured from
	 * the top of the shape. Used to clip or mask a square image into a
	 * rounded shape.
	 */
	public static int cornerInset(int radius, int row) {
		if (radius <= 0 || row >= radius) {
			return 0;
		}
		int remaining = radius - (row + 1);
		double squared = (double) radius * radius - (double) remaining * remaining;
		return radius - (int) Math.round(Math.sqrt(Math.max(0.0, squared)));
	}

	// -------------------------------------------------------------- surfaces

	/**
	 * Rounded rectangle whose colour is interpolated row by row from
	 * {@code topColor} to {@code bottomColor}.
	 *
	 * Rows are drawn one at a time (never overlapping), so the fill composites
	 * exactly once against whatever is behind it and the rounded corners fall
	 * out of the per-row inset.
	 */
	public static void surface(UiCanvas c, int x, int y, int w, int h, int radius, int topColor, int bottomColor) {
		if (w <= 0 || h <= 0) {
			return;
		}
		int r = clampRadius(radius, w, h);
		boolean flat = topColor == bottomColor || h <= 1;
		for (int i = 0; i < h; i++) {
			int color = flat ? topColor : lerpColor(topColor, bottomColor, i / (float) (h - 1));
			int inset = r <= 0 ? 0 : Math.max(cornerInset(r, i), cornerInset(r, h - 1 - i));
			c.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
		}
	}

	/** Flat rounded rectangle. */
	public static void surface(UiCanvas c, int x, int y, int w, int h, int radius, int color) {
		roundRect(c, x, y, w, h, radius, color);
	}

	/**
	 * One pixel rim whose colour is interpolated from top to bottom. Used for
	 * the hairline border of a surface and for accent lines.
	 */
	public static void surfaceEdge(UiCanvas c, int x, int y, int w, int h, int radius, int topColor, int bottomColor) {
		if (w <= 0 || h <= 0) {
			return;
		}
		int r = clampRadius(radius, w, h);
		boolean flat = topColor == bottomColor || h <= 1;
		for (int i = 0; i < h; i++) {
			int color = flat ? topColor : lerpColor(topColor, bottomColor, i / (float) (h - 1));
			int inset = r <= 0 ? 0 : Math.max(cornerInset(r, i), cornerInset(r, h - 1 - i));
			c.fill(x + inset, y + i, x + inset + 1, y + i + 1, color);
			c.fill(x + w - inset - 1, y + i, x + w - inset, y + i + 1, color);
			if (i == 0 || i == h - 1) {
				c.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
			}
		}
	}

	/** Uniform hairline outline. */
	public static void outline(UiCanvas c, int x, int y, int w, int h, int radius, int color) {
		surfaceEdge(c, x, y, w, h, radius, color, color);
	}

	/**
	 * The inner bevel: a highlight one pixel inside the top edge and a shade
	 * one pixel inside the bottom edge. This single detail is what makes a flat
	 * rectangle read as a surface with thickness.
	 */
	public static void innerBevel(UiCanvas c, int x, int y, int w, int h, int radius, int highlight, int shade) {
		if (w <= 2 || h <= 2) {
			return;
		}
		int r = clampRadius(radius, w, h);
		int inset = r <= 0 ? 0 : cornerInset(r, 1);
		c.fill(x + inset, y + 1, x + w - inset, y + 2, highlight);
		int bInset = r <= 0 ? 0 : cornerInset(r, h - 2);
		c.fill(x + bInset, y + h - 2, x + w - bInset, y + h - 1, shade);
	}

	/** Layered drop shadow under a floating panel. */
	public static void shadow(UiCanvas c, int x, int y, int w, int h, int radius) {
		roundRect(c, x + 4, y + 10, w, h, radius + 4, UiTheme.SHADOW_WIDE);
		roundRect(c, x + 2, y + 5, w, h, radius + 2, UiTheme.SHADOW_MID);
		roundRect(c, x + 1, y + 2, w, h, radius + 1, UiTheme.SHADOW_TIGHT);
	}

	/**
	 * A slow specular band that drifts across a surface, the way light slides
	 * over a curved sheet.
	 *
	 * @param phase 0..1 position of the band along the surface
	 */
	public static void sheen(UiCanvas c, int x, int y, int w, int h, int radius, float phase) {
		if (w <= 16 || h <= 16) {
			return;
		}
		int r = clampRadius(radius, w, h);
		int inset = Math.max(1, r - r / 2);
		int ix = x + inset;
		int iw = w - inset * 2;
		int iy = y + Math.max(1, r / 2);
		int ih = h - Math.max(2, r);
		if (iw <= 0 || ih <= 0) {
			return;
		}

		// One column at a time, each with its own alpha: the band is then
		// perfectly smooth instead of showing steps.
		final float center = phase * (iw + 120.0f) - 60.0f;
		final float halfWidth = 44.0f;
		int from = (int) Math.floor(center - halfWidth);
		int to = (int) Math.ceil(center + halfWidth);
		c.pushClip(ix, iy, iw, ih);
		for (int col = from; col <= to; col++) {
			float d = (col - center) / halfWidth;
			if (d <= -1f || d >= 1f) {
				continue;
			}
			float a = 1f - d * d;
			int alpha = Math.round(0x12 * a * a);
			if (alpha <= 0) {
				continue;
			}
			c.fill(ix + col, iy, ix + col + 1, iy + ih, (alpha << 24) | 0x00FFFFFF);
		}
		c.popClip();
	}

	/** Horizontal hairline with a soft fade at both ends. */
	public static void fadeLine(UiCanvas c, int x, int y, int w, int color) {
		if (w <= 0) {
			return;
		}
		int edge = Math.min(w / 2, 20);
		for (int i = 0; i < w; i++) {
			int a = color >>> 24;
			if (i < edge) {
				a = a * i / edge;
			} else if (i > w - edge - 1) {
				a = a * (w - 1 - i) / edge;
			}
			if (a <= 0) {
				continue;
			}
			c.fill(x + i, y, x + i + 1, y + 1, (a << 24) | (color & 0x00FFFFFF));
		}
	}

	/** A vertical gradient band with a soft horizontal falloff, used for glows. */
	public static void glow(UiCanvas c, int x, int y, int w, int h, int color, float strength) {
		int alpha = Math.round(((color >>> 24) & 0xFF) * strength);
		if (alpha <= 0) {
			return;
		}
		int shaded = (alpha << 24) | (color & 0x00FFFFFF);
		for (int i = 0; i < h; i++) {
			float t = h <= 1 ? 1f : i / (float) (h - 1);
			float falloff = (float) Math.sin(t * Math.PI);
			int a = Math.round(alpha * falloff);
			if (a <= 0) {
				continue;
			}
			c.fill(x, y + i, x + w, y + i + 1, (a << 24) | (color & 0x00FFFFFF));
		}
	}

	/**
	 * A soft radial halo: a few concentric translucent discs, largest first,
	 * which composite into a falloff that fades towards the edge.
	 */
	public static void halo(UiCanvas c, int cx, int cy, int radius, int color, float strength) {
		if (radius <= 0 || strength <= 0.01f) {
			return;
		}
		final int layers = 5;
		for (int i = layers; i >= 1; i--) {
			float t = i / (float) layers;
			int r = Math.round(radius * t);
			int a = Math.round(((color >>> 24) & 0xFF) * strength * (1f - t) * 0.55f);
			if (a <= 0) {
				continue;
			}
			circle(c, cx, cy, r, (a << 24) | (color & 0x00FFFFFF));
		}
	}

	// ------------------------------------------------------------------ text

	/** Draws text, appending an ellipsis if it does not fit maxWidth. */
	public static void drawEllipsized(UiCanvas c, UiText font, String text, int x, int y, int maxWidth, int color) {
		if (text == null || text.isEmpty() || maxWidth <= 0) {
			return;
		}
		String s = text;
		if (font.width(s) > maxWidth) {
			String ell = "…";
			if (font.width(ell) > maxWidth) {
				ell = "...";
			}
			while (s.length() > 1 && font.width(s + ell) > maxWidth) {
				s = s.substring(0, s.length() - 1);
			}
			s = s + ell;
		}
		font.draw(c, s, x, y, color);
	}

	/** Draws text right-aligned to x2. */
	public static void drawRightAligned(UiCanvas c, UiText font, String text, int x2, int y, int color) {
		if (text == null || text.isEmpty()) {
			return;
		}
		font.draw(c, text, x2 - font.width(text), y, color);
	}

	/** Draws text centered horizontally on x. */
	public static void drawCentered(UiCanvas c, UiText font, String text, int x, int y, int color) {
		if (text == null || text.isEmpty()) {
			return;
		}
		font.draw(c, text, x - font.width(text) / 2, y, color);
	}

	// ------------------------------------------------------------- utilities

	private static int clampRadius(int radius, int w, int h) {
		return Math.max(0, Math.min(radius, Math.min(w / 2, h / 2)));
	}

	/** Linear blend between two ARGB colours (t=0 -> a, t=1 -> b). */
	public static int lerpColor(int a, int b, float t) {
		t = Math.max(0.0f, Math.min(1.0f, t));
		int aA = (a >>> 24) & 0xFF, aR = (a >>> 16) & 0xFF, aG = (a >>> 8) & 0xFF, aB = a & 0xFF;
		int bA = (b >>> 24) & 0xFF, bR = (b >>> 16) & 0xFF, bG = (b >>> 8) & 0xFF, bB = b & 0xFF;
		int cA = (int) (aA + (bA - aA) * t);
		int cR = (int) (aR + (bR - aR) * t);
		int cG = (int) (aG + (bG - aG) * t);
		int cB = (int) (aB + (bB - aB) * t);
		return (cA << 24) | (cR << 16) | (cG << 8) | cB;
	}

	/** Scales a colour's alpha channel (0..255), keeping RGB. */
	public static int withAlpha(int color, int alpha) {
		int a = Math.max(0, Math.min(255, alpha));
		return (a << 24) | (color & 0x00FFFFFF);
	}

	/** Multiplies a colour's alpha channel, keeping RGB. */
	public static int fadeAlpha(int color, float factor) {
		int a = Math.round(((color >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, factor)));
		return (a << 24) | (color & 0x00FFFFFF);
	}

	/** Clamps a float into the 0..1 range. */
	public static float clamp01(float value) {
		return value < 0f ? 0f : (value > 1f ? 1f : value);
	}

	/** Smooth 0..1 ramp used by every fade in the GUI. */
	public static float smoothstep(float edge0, float edge1, float value) {
		if (edge1 <= edge0) {
			return value < edge0 ? 0f : 1f;
		}
		float t = clamp01((value - edge0) / (edge1 - edge0));
		return t * t * (3f - 2f * t);
	}
}
