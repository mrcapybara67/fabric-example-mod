package com.capy.client.gui;


/**
 * Minimal geometric icons drawn from primitives (no textures), sharing one
 * visual style and stroke weight so the interface reads as a set.
 *
 * Nothing here paints an opaque "knock-out" colour: every icon is a set of
 * strokes or small filled shapes, so icons stay legible on top of the plum
 * surfaces where the colour behind them may be anythinc.
 *
 * The cherry blossom motif is the client's signature: it appears in the header
 * mark, in the empty states and as the drifting petal particles that play
 * while the GUI opens.
 */
public final class UiIcons {
	private UiIcons() {
	}

	/** Draws the given icon into the box (x, y, size, size). */
	public static void draw(UiCanvas c, Icon icon, int x, int y, int size, int color) {
		switch (icon) {
			case SEARCH -> search(c, x, y, size, color);
			case CLOSE -> close(c, x, y, size, color);
			case BLOSSOM -> blossom(c, x, y, size, color);
			case ALERT -> alert(c, x, y, size, color);
			case PLUS -> plus(c, x, y, size, color);
			case CHECK -> check(c, x, y, size, color);
			case CHEVRON -> chevron(c, x, y, size, color, 0f);
		}
	}

	/**
	 * Draws the chevron rotated between 0 (pointing right, collapsed) and
	 * 1 (pointing down, expanded) by {@code openness}.
	 */
	public static void chevron(UiCanvas c, int x, int y, int size, int color, float openness) {
		int cx = x + size / 2;
		int cy = y + size / 2;
		int half = Math.max(2, size / 2 - 1);
		int rows = Math.max(1, half);
		for (int i = 0; i < rows; i++) {
			int spread = Math.round(half * (1f - openness));
			int dy = Math.round((i - (rows - 1) / 2f) * (0.6f + openness));
			c.fill(cx - spread, cy + dy, cx - spread + 1, cy + dy + 1, color);
			c.fill(cx + spread - 1, cy + dy, cx + spread, cy + dy + 1, color);
		}
	}

	public enum Icon {
		SEARCH,
		CLOSE,
		BLOSSOM,
		ALERT,
		PLUS,
		CHECK,
		CHEVRON
	}






	// ---- magnifier: circle + handle ---------------------------------------
	private static void search(UiCanvas c, int x, int y, int s, int col) {
		int r = Math.max(2, (s - 3) / 2);
		circleOutline(c, x, y, r * 2, col);
		int hx = x + r * 2 - 1;
		c.fill(hx + 1, y + r * 2 - 1, hx + 3, y + r * 2 + 2, col);
		c.fill(hx + 2, y + r * 2, hx + 3, y + r * 2 + 2, col);
	}

	// ---- close: x mark ------------------------------------------------------
	private static void close(UiCanvas c, int x, int y, int s, int col) {
		int r = Math.max(1, s / 4);
		int cx = x + s / 2;
		int cy = y + s / 2;
		for (int i = -r; i <= r; i++) {
			c.fill(cx + i, cy + i, cx + i + 1, cy + i + 1, col);
			c.fill(cx + i, cy - i, cx + i + 1, cy - i + 1, col);
		}
	}

	// ---- cherry blossom: five petals around a stamen centre ---------------
	private static void blossom(UiCanvas c, int x, int y, int s, int col) {
		int cx = x + s / 2;
		int cy = y + s / 2;
		float petal = Math.max(1.6f, s * 0.20f);
		float reach = s * 0.20f;
		for (int i = 0; i < 5; i++) {
			double angle = -Math.PI / 2 + i * (2.0 * Math.PI / 5.0);
			int px = cx + (int) Math.round(Math.cos(angle) * reach);
			int py = cy + (int) Math.round(Math.sin(angle) * reach);
			disc(c, px, py, petal, col);
		}
		disc(c, cx, cy, Math.max(1.2f, s * 0.09f), col);
	}


	// ---- alert: warning triangle with an exclamation mark ------------------
	private static void alert(UiCanvas c, int x, int y, int s, int col) {
		int apex = y + 1;
		int base = y + s - 1;
		int half = Math.max(2, s / 2 - 1);
		int cx = x + s / 2;
		for (int row = 0; row < base - apex; row++) {
			int yy = apex + row;
			int spread = Math.round(half * (row / (float) Math.max(1, base - apex - 1)));
			c.fill(cx - spread, yy, cx - spread + 1, yy + 1, col);
			c.fill(cx + spread, yy, cx + spread + 1, yy + 1, col);
		}
		c.fill(cx - half, base - 1, cx + half + 1, base, col);
		int bar = Math.max(2, s / 4);
		c.fill(cx, apex + 3, cx + 1, apex + 3 + bar, col);
		c.fill(cx, base - 3, cx + 1, base - 2, col);
	}



	// ---- plus ----------------------------------------------------------------
	private static void plus(UiCanvas c, int x, int y, int s, int col) {
		int cx = x + s / 2;
		int cy = y + s / 2;
		int r = Math.max(2, s / 2 - 1);
		c.fill(cx - r, cy, cx + r + 1, cy + 1, col);
		c.fill(cx, cy - r, cx + 1, cy + r + 1, col);
	}


	// ---- check mark ------------------------------------------------------------
	private static void check(UiCanvas c, int x, int y, int s, int col) {
		int x0 = x + 2;
		int y0 = y + s / 2;
		int x1 = x + s / 2 - 1;
		int y1 = y + s - 2;
		int x2 = x + s - 2;
		int y2 = y + 2;
		stroke(c, x0, y0, x1, y1, col);
		stroke(c, x1, y1, x2, y2, col);
	}

	/** Bresenham line, one pixel wide. */
	private static void stroke(UiCanvas c, int x0, int y0, int x1, int y1, int col) {
		int dx = Math.abs(x1 - x0);
		int sx = x0 < x1 ? 1 : -1;
		int dy = -Math.abs(y1 - y0);
		int sy = y0 < y1 ? 1 : -1;
		int err = dx + dy;
		while (true) {
			c.fill(x0, y0, x0 + 1, y0 + 1, col);
			if (x0 == x1 && y0 == y1) {
				return;
			}
			int e2 = 2 * err;
			if (e2 >= dy) {
				err += dy;
				x0 += sx;
			}
			if (e2 <= dx) {
				err += dx;
				y0 += sy;
			}
		}
	}

	// -------------------------------------------------------------- primitives

	/** Cheap circle outline from row spans. */
	private static void circleOutline(UiCanvas c, int x, int y, int s, int col) {
		int r = s / 2;
		if (r < 2) {
			c.fill(x, y, x + s, y + s, col);
			return;
		}
		for (int dy = 0; dy < s; dy++) {
			int oy = dy - r + 1;
			int dx = (int) Math.round(Math.sqrt(r * (double) r - oy * (double) oy));
			c.fill(x + r - dx, y + dy, x + r - dx + 1, y + dy + 1, col);
			c.fill(x + r + dx - 1, y + dy, x + r + dx, y + dy + 1, col);
		}
	}

	/** Filled circle centred on (cx, cy). */
	private static void disc(UiCanvas c, int cx, int cy, float radius, int col) {
		int r = Math.max(1, Math.round(radius));
		for (int dy = -r; dy <= r; dy++) {
			int dx = (int) Math.round(Math.sqrt(radius * radius - (float) (dy * dy)));
			if (dx < 0) {
				continue;
			}
			c.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, col);
		}
	}

	/**
	 * A single cherry petal: a soft almond shape used by the drifting petal
	 * particles. Drawn as an ellipse squashed to half the height.
	 */
	public static void petal(UiCanvas c, float cx, float cy, float size, float rotation, int color) {
		int r = Math.max(1, Math.round(size));
		for (int dy = -r; dy <= r; dy++) {
			float f = dy / (float) r;
			float dx = (float) r * (float) Math.sqrt(Math.max(0f, 1f - f * f));
			if (dx < 0.5f) {
				continue;
			}
			// rotate the ellipse a little so the petal does not look like a dot
			double a = rotation;
			float rx = dx;
			float ry = dy * 0.62f;
			float px = rx * (float) Math.cos(a) - ry * (float) Math.sin(a);
			float py = rx * (float) Math.sin(a) + ry * (float) Math.cos(a);
			int x0 = (int) Math.round(cx + px) - 1;
			int y0 = (int) Math.round(cy + py);
			c.fill(x0, y0, x0 + 2, y0 + 2, color);
		}
	}
}
