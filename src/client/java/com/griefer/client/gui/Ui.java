package com.griefer.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;

/**
 * Shared drawing primitives for the GUI. Every component renders through
 * these so corner radii, clipping and text ellipsis behave identically
 * everywhere. Rounded corners are composed from axis-aligned fills — no
 * shaders, no textures, no per-frame allocations.
 */
public final class Ui {
	/** Max simultaneous clips (must exceed the deepest nesting we ever use). */
	private static final int MAX_CLIP_DEPTH = 8;
	private static final boolean[] CLIP_USED = new boolean[MAX_CLIP_DEPTH];

	private Ui() {
	}

	/** Filled rounded rectangle built from 5 overlapping rectangles. */
	public static void roundRect(GuiGraphics g, int x, int y, int w, int h, int radius, int color) {
		if (w <= 0 || h <= 0) {
			return;
		}
		int r = Math.min(radius, Math.min(w / 2, h / 2));
		if (r <= 0) {
			g.fill(x, y, x + w, y + h, color);
			return;
		}
		g.fill(x + r, y, x + w - r, y + h, color);
		g.fill(x, y + r, x + w, y + h - r, color);
		g.fill(x, y, x + r, y + r, color);
		g.fill(x + w - r, y, x + w, y + r, color);
		g.fill(x, y + h - r, x + w, y + h, color);
	}

	/** Rounded-rectangle outline drawn as 4 thin edge fills. */
	public static void roundOutline(GuiGraphics g, int x, int y, int w, int h, int radius, int color) {
		if (w <= 0 || h <= 0) {
			return;
		}
		g.fill(x, y, x + w, y + 1, color);
		g.fill(x, y + h - 1, x + w, y + h, color);
		g.fill(x, y + 1, x + 1, y + h - 1, color);
		g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
	}

	/** Soft drop shadow (single translucent band under the shape). */
	public static void shadow(GuiGraphics g, int x, int y, int w, int h, int radius) {
		roundRect(g, x + 1, y + 2, w, h, radius, UiTheme.SHADOW);
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
			String ell = "...";
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
