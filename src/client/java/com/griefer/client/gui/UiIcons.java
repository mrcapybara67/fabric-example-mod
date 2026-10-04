package com.griefer.client.gui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Minimal geometric icons drawn from primitives (no textures), sharing one
 * visual style and stroke weight so the sidebar reads as a set.
 *
 * Nothing here paints an opaque "knock-out" colour: every icon is a set of
 * strokes, so icons stay legible on top of translucent glass where the colour
 * behind them is the game itself.
 */
public final class UiIcons {
	private UiIcons() {
	}

	/** Draws the given icon into the box (x, y, size, size). */
	public static void draw(GuiGraphics g, Icon icon, int x, int y, int size, int color) {
		switch (icon) {
			case TARGET -> target(g, x, y, size, color);
			case WORLD -> world(g, x, y, size, color);
			case VISUAL -> visual(g, x, y, size, color);
			case DONUT -> donut(g, x, y, size, color);
			case MISC -> misc(g, x, y, size, color);
			case SEARCH -> search(g, x, y, size, color);
			case CLOSE -> close(g, x, y, size, color);
			case CHEVRON -> chevron(g, x, y, size, color, 0f);
		}
	}

	/**
	 * Draws the chevron rotated between 0 (pointing right, collapsed) and
	 * 1 (pointing down, expanded) by {@code openness}.
	 */
	public static void chevron(GuiGraphics g, int x, int y, int size, int color, float openness) {
		int cx = x + size / 2;
		int cy = y + size / 2;
		int half = Math.max(2, size / 2 - 1);
		int rows = Math.max(1, half);
		for (int i = 0; i < rows; i++) {
			// Collapse the horizontal spread as the chevron rotates downward.
			int spread = Math.round(half * (1f - openness));
			int dy = Math.round((i - (rows - 1) / 2f) * (0.6f + openness));
			g.fill(cx - spread, cy + dy, cx - spread + 1, cy + dy + 1, color);
			g.fill(cx + spread - 1, cy + dy, cx + spread, cy + dy + 1, color);
		}
	}

	public enum Icon {
		TARGET,
		WORLD,
		VISUAL,
		DONUT,
		MISC,
		SEARCH,
		CLOSE,
		CHEVRON
	}

	// ---- target: ring + center dot ---------------------------------------
	private static void target(GuiGraphics g, int x, int y, int s, int c) {
		circleOutline(g, x, y, s, c);
		int cx = x + s / 2 - 1;
		g.fill(cx, y + s / 2 - 1, cx + 2, y + s / 2 + 1, c);
	}

	// ---- globe: circle + vertical arcs + equator --------------------------
	private static void world(GuiGraphics g, int x, int y, int s, int c) {
		circleOutline(g, x, y, s, c);
		int cx = x + s / 2;
		int cy = y + s / 2;
		int arc = Math.max(1, s / 4);
		// Vertical arc: two columns inset from the outer circle.
		for (int dy = -arc; dy <= arc; dy++) {
			int row = cy + dy;
			if (row < y || row >= y + s) {
				continue;
			}
			g.fill(cx - arc, row, cx - arc + 1, row + 1, c);
			g.fill(cx + arc - 1, row, cx + arc, row + 1, c);
		}
		g.fill(x + 2, cy, x + s - 2, cy + 1, c);
	}

	// ---- eye: lens outline + pupil -----------------------------------------
	private static void visual(GuiGraphics g, int x, int y, int s, int c) {
		int cy = y + s / 2;
		// Lens: two arcs meeting at the corners, drawn as short strokes.
		for (int i = 0; i < s; i++) {
			int dy = Math.abs(i - s / 2);
			int inset = (int) Math.round((dy * dy) / (float) Math.max(1, s / 2));
			if (inset >= 1) {
				g.fill(x + i, cy - inset, x + i + 1, cy - inset + 1, c);
				g.fill(x + i, cy + inset - 1, x + i + 1, cy + inset, c);
			}
		}
		int px = x + s / 2 - 1;
		g.fill(px, cy - 2, px + 3, cy + 2, c);
	}

	// ---- torus: ring (interior transparent, reads as the hole) -----------
	private static void donut(GuiGraphics g, int x, int y, int s, int c) {
		circleOutline(g, x, y, s, c);
		int cx = x + s / 2;
		int cy = y + s / 2;
		g.fill(cx - 1, y + 1, cx + 2, y + 2, c);
		g.fill(x + 1, cy - 1, x + s - 1, cy + 2, c);
	}

	// ---- sliders: three lines with offset knobs ----------------------------
	private static void misc(GuiGraphics g, int x, int y, int s, int c) {
		for (int row = 0; row < 3; row++) {
			int ly = y + 3 + row * 4;
			g.fill(x + 1, ly, x + s - 1, ly + 1, c);
			int kx = x + 2 + ((row * 5) % Math.max(1, s - 5));
			g.fill(kx, ly - 1, kx + 2, ly + 2, c);
		}
	}

	// ---- magnifier: circle + handle ---------------------------------------
	private static void search(GuiGraphics g, int x, int y, int s, int c) {
		int r = Math.max(2, (s - 3) / 2);
		circleOutline(g, x, y, r * 2, c);
		int hx = x + r * 2 - 1;
		g.fill(hx + 1, y + r * 2 - 1, hx + 3, y + r * 2 + 2, c);
		g.fill(hx + 2, y + r * 2, hx + 3, y + r * 2 + 2, c);
	}

	// ---- close: x mark ------------------------------------------------------
	private static void close(GuiGraphics g, int x, int y, int s, int c) {
		int r = Math.max(1, s / 4);
		int cx = x + s / 2;
		int cy = y + s / 2;
		for (int i = -r; i <= r; i++) {
			g.fill(cx + i, cy + i, cx + i + 1, cy + i + 1, c);
			g.fill(cx + i, cy - i, cx + i + 1, cy - i + 1, c);
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
