package com.griefer.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Shared drawing primitives. Every component renders through this layer so
 * corner radii, shadows, clipping, icons and text ellipsis behave identically
 * everywhere. Rounded rects are composed per-pixel at the corners (clean,
 * seam-free, no shaders or textures); everything else is plain fills.
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
		int r = Math.min(radius, Math.min(w / 2, h / 2));
		if (r <= 0) {
			g.fill(x, y, x + w, y + h, color);
			return;
		}
		// Center band
		g.fill(x + r, y, x + w - r, y + h, color);
		// Left / right bands between the corner rows
		g.fill(x, y + r, x + r, y + h - r, color);
		g.fill(x + w - r, y + r, x + w, y + h - r, color);
		// Corner rows: each row insets by its circular offset (both sides at once)
		for (int i = 0; i < r; i++) {
			int dy = i + 1;
			int inset = r - (int) Math.round(Math.sqrt(r * (double) r - (r - dy) * (double) (r - dy)));
			g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);           // top
			g.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, color);   // bottom
		}
	}

	/** 1px rounded outline (4 edge fills + the 2 edge pixels of each corner row). */
	public static void roundOutline(GuiGraphics g, int x, int y, int w, int h, int radius, int color) {
		if (w <= 0 || h <= 0) {
			return;
		}
		int r = Math.min(radius, Math.min(w / 2, h / 2));
		if (r <= 0) {
			g.fill(x, y, x + w, y + 1, color);
			g.fill(x, y + h - 1, x + w, y + h, color);
			g.fill(x, y + 1, x + 1, y + h - 1, color);
			g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
			return;
		}
		g.fill(x + r, y, x + w - r, y + 1, color);
		g.fill(x + r, y + h - 1, x + w - r, y + h, color);
		g.fill(x, y + r, x + 1, y + h - r, color);
		g.fill(x + w - 1, y + r, x + w, y + h - r, color);
		for (int i = 0; i < r; i++) {
			int dy = i + 1;
			int inset = r - (int) Math.round(Math.sqrt(r * (double) r - (r - dy) * (double) (r - dy)));
			// only the two edge pixels per corner row (transparent fills cannot carve)
			g.fill(x + inset, y + i, x + inset + 1, y + i + 1, color);
			g.fill(x + w - inset - 1, y + i, x + w - inset, y + i + 1, color);
			g.fill(x + inset, y + h - 1 - i, x + inset + 1, y + h - i, color);
			g.fill(x + w - inset - 1, y + h - 1 - i, x + w - inset, y + h - i, color);
		}
	}

	/** Two-layer soft drop shadow (large faint + tight tighter band). */
	public static void shadow(GuiGraphics g, int x, int y, int w, int h, int radius) {
		roundRect(g, x + 3, y + 5, w, h, radius + 2, 0x38000000);
		roundRect(g, x + 1, y + 2, w, h, radius + 1, 0x30000000);
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

	/** Linear blend between two ARGB colors (t=0 -> a, t=1 -> b). */
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
}
