package com.griefer.client.gui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Minimal geometric icons drawn from primitives (no textures), sharing one
 * visual style and stroke weight so the sidebar reads as a set.
 */
public final class UiIcons {
	private UiIcons() {
	}

	/** Draws the given icon centered in a box at (x, y, size, size). */
	public static void draw(GuiGraphics g, Icon icon, int x, int y, int size, int color) {
		switch (icon) {
			case TARGET -> target(g, x, y, size, color);
			case WORLD -> world(g, x, y, size, color);
			case VISUAL -> visual(g, x, y, size, color);
			case DONUT -> donut(g, x, y, size, color);
			case MISC -> misc(g, x, y, size, color);
			case SEARCH -> search(g, x, y, size, color);
			case CLOSE -> close(g, x, y, size, color);
		}
	}

	public enum Icon {
		TARGET,
		WORLD,
		VISUAL,
		DONUT,
		MISC,
		SEARCH,
		CLOSE
	}

	// ---- target: ring + center dot ---------------------------------------
	private static void target(GuiGraphics g, int x, int y, int s, int c) {
		circleOutline(g, x, y, s, c);
		int cx = x + s / 2 - 1;
		g.fill(cx, y + s / 2 - 1, cx + 2, y + s / 2 + 1, c);
	}

	// ---- globe: circle + vertical ellipse + equator ----------------------
	private static void world(GuiGraphics g, int x, int y, int s, int c) {
		circleOutline(g, x, y, s, c);
		int cx = x + s / 2;
		int w = Math.max(1, s / 4);
		g.fill(cx - w, y + 2, cx - w + 1, y + s - 2, c);
		g.fill(cx + w, y + 2, cx + w + 1, y + s - 2, c);
		g.fill(x + 2, y + s / 2, x + s - 2, y + s / 2 + 1, c);
	}

	// ---- eye: almond outline + pupil --------------------------------------
	private static void visual(GuiGraphics g, int x, int y, int s, int c) {
		int cy = y + s / 2 - 1;
		g.fill(x + 1, cy, x + s - 1, cy + 2, c);
		int px = x + s / 2 - 1;
		g.fill(px, cy - 2, px + 2, cy + 4, c);
		g.fill(px + 1, cy - 1, px + 2, cy + 3, UiTheme.BG);
	}

	// ---- torus: ring (interior stays transparent, reads as the hole) ------
	private static void donut(GuiGraphics g, int x, int y, int s, int c) {
		circleOutline(g, x, y, s, c);
	}

	// ---- sliders: three lines with offset knobs -----------------------------
	private static void misc(GuiGraphics g, int x, int y, int s, int c) {
		for (int row = 0; row < 3; row++) {
			int ly = y + 3 + row * 4;
			g.fill(x + 1, ly, x + s - 1, ly + 1, c);
			int kx = x + 2 + ((row * 5) % Math.max(1, s - 5));
			g.fill(kx, ly - 1, kx + 2, ly + 2, c);
		}
	}

	// ---- magnifier: circle + handle -----------------------------------------
	private static void search(GuiGraphics g, int x, int y, int s, int c) {
		int d = s - 4;
		circleOutline(g, x, y, d + 2, c);
		g.fill(x + d, y + d - 1, x + d + 3, y + d + 2, c);
		g.fill(x + d + 1, y + d, x + d + 3, y + d + 2, c);
	}

	// ---- close: x mark --------------------------------------------------------
	private static void close(GuiGraphics g, int x, int y, int s, int c) {
		int r = Math.max(1, s / 4);
		for (int i = -r; i <= r; i++) {
			g.fill(x + s / 2 + i, y + s / 2 + i, x + s / 2 + i + 1, y + s / 2 + i + 1, c);
			g.fill(x + s / 2 + i, y + s / 2 - i, x + s / 2 + i + 1, y + s / 2 - i + 1, c);
		}
	}

	/** Cheap circle outline from row spans. */
	private static void circleOutline(GuiGraphics g, int x, int y, int s, int c) {
		int r = s / 2;
		if (r < 2) {
			g.fill(x, y, x + s, y + s, c);
			return;
		}
		for (int dy = 0; dy < s; dy++) {
			int oy = dy - r + 1;
			int dx = (int) Math.round(Math.sqrt(r * (double) r - oy * (double) oy));
			g.fill(x + r - dx, y + dy, x + r - dx + 1, y + dy + 1, c);
			g.fill(x + r + dx - 1, y + dy, x + r + dx, y + dy + 1, c);
		}
	}
}
