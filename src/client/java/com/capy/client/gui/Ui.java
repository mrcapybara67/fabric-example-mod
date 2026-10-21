package com.capy.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Shared drawing primitives. Every component renders through this layer so
 * corner radii, surface layering, shadows, clipping, icons and text ellipsis
 * behave identically everywhere.
 *
 * There is no shader and no blur: the whole GUI is built from plain fills
 * because that is all {@link GuiGraphics} offers. The premium look comes from
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
	/** Max simultaneous clips (exceeds the deepest nesting we ever use). */
	private static final int MAX_CLIP_DEPTH = 8;
	private static final boolean[] CLIP_USED = new boolean[MAX_CLIP_DEPTH];

	private Ui() {
	}

	/** Filled rounded rectangle with true per-pixel corners. */
	public static void roundRect(GuiGraphics g, int x, int y, int w, int h, int radius, int color) {
		if (w <= 0 || h <= 0) {
			return;
		}
		int r = clampRadius(radius, w, h);
		if (r <= 0) {
			g.fill(x, y, x + w, y + h, color);
			return;
		}
		g.fill(x + r, y, x + w - r, y + h, color);
		g.fill(x, y + r, x + r, y + h - r, color);
		g.fill(x + w - r, y + r, x + w, y + h - r, color);
		for (int i = 0; i < r; i++) {
			int inset = cornerInset(r, i);
			g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
			g.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, color);
		}
	}

	// ---------------------------------------------------------------- surfaces

	/**
	 * Rounded rectangle whose colour is interpolated row by row from
	 * {@code topColor} to {@code bottomColor}.
	 *
	 * Rows are drawn one at a time (never overlapping), so the fill composites
	 * exactly once against whatever is behind it and the rounded corners fall
	 * out of the per-row inset.
	 */
	public static void surface(GuiGraphics g, int x, int y, int w, int h, int radius, int topColor, int bottomColor) {
		if (w <= 0 || h <= 0) {
			return;
		}
		int r = clampRadius(radius, w, h);
		boolean flat = topColor == bottomColor || h <= 1;
		for (int i = 0; i < h; i++) {
			int color = flat ? topColor : lerpColor(topColor, bottomColor, i / (float) (h - 1));
			int inset = r <= 0 ? 0 : Math.max(cornerInset(r, i), cornerInset(r, h - 1 - i));
			g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
		}
	}

	/** Flat rounded rectangle. */
	public static void surface(GuiGraphics g, int x, int y, int w, int h, int radius, int color) {
		roundRect(g, x, y, w, h, radius, color);
	}

	/**
	 * One pixel rim whose colour is interpolated from top to bottom. Used for
	 * the hairline border of a surface and for accent lines.
	 */
	public static void surfaceEdge(GuiGraphics g, int x, int y, int w, int h, int radius, int topColor, int bottomColor) {
		if (w <= 0 || h <= 0) {
			return;
		}
		int r = clampRadius(radius, w, h);
		boolean flat = topColor == bottomColor || h <= 1;
		for (int i = 0; i < h; i++) {
			int color = flat ? topColor : lerpColor(topColor, bottomColor, i / (float) (h - 1));
			int inset = r <= 0 ? 0 : Math.max(cornerInset(r, i), cornerInset(r, h - 1 - i));
			g.fill(x + inset, y + i, x + inset + 1, y + i + 1, color);
			g.fill(x + w - inset - 1, y + i, x + w - inset, y + i + 1, color);
			if (i == 0 || i == h - 1) {
				g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
			}
		}
	}

	/** Uniform hairline outline. */
	public static void outline(GuiGraphics g, int x, int y, int w, int h, int radius, int color) {
		surfaceEdge(g, x, y, w, h, radius, color, color);
	}

	/**
	 * The inner bevel: a highlight one pixel inside the top edge and a shade
	 * one pixel inside the bottom edge. This single detail is what makes a flat
	 * rectangle read as a surface with thickness.
	 */
	public static void innerBevel(GuiGraphics g, int x, int y, int w, int h, int radius, int highlight, int shade) {
		if (w <= 2 || h <= 2) {
			return;
		}
		int r = clampRadius(radius, w, h);
		int inset = r <= 0 ? 0 : cornerInset(r, 1);
		g.fill(x + inset, y + 1, x + w - inset, y + 2, highlight);
		int bInset = r <= 0 ? 0 : cornerInset(r, h - 2);
		g.fill(x + bInset, y + h - 2, x + w - bInset, y + h - 1, shade);
	}

	/** Layered drop shadow under a floating panel. */
	public static void shadow(GuiGraphics g, int x, int y, int w, int h, int radius) {
		roundRect(g, x + 4, y + 10, w, h, radius + 4, UiTheme.SHADOW_WIDE);
		roundRect(g, x + 2, y + 5, w, h, radius + 2, UiTheme.SHADOW_MID);
		roundRect(g, x + 1, y + 2, w, h, radius + 1, UiTheme.SHADOW_TIGHT);
	}

	/**
	 * A slow specular band that drifts across a surface, the way light slides
	 * over a curved sheet.
	 *
	 * @param phase 0..1 position of the band along the surface
	 */
	public static void sheen(GuiGraphics g, int x, int y, int w, int h, int radius, float phase) {
		if (w <= 8 || h <= 8) {
			return;
		}
		int r = clampRadius(radius, w, h);
		int ix = x + Math.max(1, r - r / 2);
		int iw = w - 2 * Math.max(1, r - r / 2);
		int iy = y + Math.max(1, r / 2);
		int ih = h - Math.max(2, r);
		if (iw <= 0 || ih <= 0) {
			return;
		}

		final int strips = 12;
		final float center = phase * (iw + 90.0f) - 45.0f;
		final float halfWidth = 34.0f;
		if (!beginClip(g, ix, iy, iw, ih)) {
			return;
		}
		for (int i = 0; i < strips; i++) {
			float t = (i + 0.5f) / strips;
			float px = center + (t - 0.5f) * halfWidth * 2.0f;
			float a = (float) (Math.sin(t * Math.PI) * Math.sin(t * Math.PI));
			int alpha = Math.round(0x14 * a);
			if (alpha <= 0) {
				continue;
			}
			g.fill(ix + (int) px, iy, ix + (int) px + 2, iy + ih, (alpha << 24) | 0x00FFFFFF);
		}
		endClip(g);
	}

	/** Horizontal hairline with a soft fade at both ends. */
	public static void fadeLine(GuiGraphics g, int x, int y, int w, int color) {
		if (w <= 0) {
			return;
		}
		int edge = Math.min(w / 2, 16);
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
			g.fill(x + i, y, x + i + 1, y + 1, (a << 24) | (color & 0x00FFFFFF));
		}
	}

	// -------------------------------------------------------------- utilities

	/** Horizontal inset of a rounded corner at row {@code i} from the top. */
	private static int cornerInset(int r, int i) {
		if (r <= 0 || i >= r) {
			return 0;
		}
		int dy = i + 1;
		int remaining = r - dy;
		return r - (int) Math.round(Math.sqrt(r * (double) r - (double) remaining * remaining));
	}

	private static int clampRadius(int radius, int w, int h) {
		return Math.max(0, Math.min(radius, Math.min(w / 2, h / 2)));
	}

	/** Begins a clip region. Returns false if nesting depth was exhausted. */
	public static boolean beginClip(GuiGraphics g, int x, int y, int w, int h) {
		for (int i = 0; i < MAX_CLIP_DEPTH; i++) {
			if (!CLIP_USED[i]) {
				CLIP_USED[i] = true;
				g.enableScissor(x, y, x + w, y + h);
				return true;
			}
		}
		return false;
	}

	/** Ends the most recent clip region. */
	public static void endClip(GuiGraphics g) {
		for (int i = MAX_CLIP_DEPTH - 1; i >= 0; i--) {
			if (CLIP_USED[i]) {
				CLIP_USED[i] = false;
				g.disableScissor();
				return;
			}
		}
	}

	/** Draws text, appending an ellipsis if it does not fit maxWidth. */
	public static void drawEllipsized(GuiGraphics g, Font font, String text, int x, int y, int maxWidth, int color) {
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
		g.drawString(font, s, x, y, color, false);
	}

	/** Draws text right-aligned to x2. */
	public static void drawRightAligned(GuiGraphics g, Font font, String text, int x2, int y, int color) {
		g.drawString(font, text, x2 - font.width(text), y, color, false);
	}

	/** Draws text centered horizontally on x. */
	public static void drawCentered(GuiGraphics g, Font font, String text, int x, int y, int color) {
		g.drawString(font, text, x - font.width(text) / 2, y, color, false);
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
