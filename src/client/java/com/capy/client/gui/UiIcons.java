package com.capy.client.gui;


/**
 * Minimal geometric icons drawn from primitives (no textures), sharing one
 * visual style and stroke weight so the interface reads as a set.
 *
 * Nothing here paints an opaque "knock-out" colour: every icon is a set of
 * strokes or small filled shapes, so icons stay legible on top of the plum
 * surfaces where the colour behind them may be anything.
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
			case CREST -> crest(c, x, y, size, color);
			case ALERT -> alert(c, x, y, size, color);
			case PLUS -> plus(c, x, y, size, color);
			case CHECK -> check(c, x, y, size, color);
			case CHEVRON -> chevron(c, x, y, size, color, 0f);
			case SUN -> sun(c, x, y, size, color);
			case RAIN -> rain(c, x, y, size, color);
			case THUNDER -> thunder(c, x, y, size, color);
			case COMPASS -> compass(c, x, y, size, color);
			case GLOBE -> globe(c, x, y, size, color);
			case EYE -> eye(c, x, y, size, color);
			case DONUT -> donut(c, x, y, size, color);
			case SLIDERS -> sliders(c, x, y, size, color);
			case LAYOUT -> layout(c, x, y, size, color);
			case PALETTE -> palette(c, x, y, size, color);
			case BACK -> back(c, x, y, size, color);
		}
	}

	/**
	 * Draws the chevron rotated between 0 (pointing right, collapsed) and
	 * 1 (pointing down, expanded) by {@code openness}.
	 *
	 * It is two solid arms, not two columns of single pixels: at twelve pixels
	 * a one-pixel arm reads as three stray dots, which is what the expander
	 * looked like before.
	 */
	public static void chevron(UiCanvas c, int x, int y, int size, int color, float openness) {
		float t = Math.max(0f, Math.min(1f, openness));
		int cx = x + size / 2;
		int cy = y + size / 2;
		int half = Math.max(2, size / 2 - 1);

		// The shape is a V that pivots about its top-left corner: collapsed it
		// points right, expanded it points down. Only the joint and the free
		// end move, which is what makes the transition read as one rotation.
		int lx = cx - half + 1;
		int ly = cy - half + 1;
		int jointX = Math.round((cx + half - 2) * (1f - t) + cx * t);
		int jointY = Math.round(cy * (1f - t) + (cy + half - 2) * t);
		int endX = Math.round((lx) * (1f - t) + (cx + half - 1) * t);
		int endY = Math.round((cy + half - 1) * (1f - t) + ly * t);

		arm(c, jointX, jointY, lx, ly, color);
		arm(c, jointX, jointY, endX, endY, color);
	}

	/** A two-pixel thick line, drawn one step at a time so it stays crisp. */
	private static void arm(UiCanvas c, int x0, int y0, int x1, int y1, int color) {
		int steps = Math.max(1, Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)));
		int thickness = 2;
		for (int i = 0; i <= steps; i++) {
			int px = x0 + Math.round((x1 - x0) * (i / (float) steps));
			int py = y0 + Math.round((y1 - y0) * (i / (float) steps));
			c.fill(px, py, px + thickness, py + thickness, color);
		}
	}

	public enum Icon {
		SEARCH,
		CLOSE,
		BLOSSOM,
		/** The client seal: a blossom inside a double ring. */
		CREST,
		ALERT,
		PLUS,
		CHECK,
		CHEVRON,
		SUN,
		RAIN,
		THUNDER,
		COMPASS,
		GLOBE,
		EYE,
		DONUT,
		SLIDERS,
		LAYOUT,
		PALETTE,
		BACK
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
		// A blossom has to read as five lobes when it is a small mark and as one
		// flower when it is a large watermark, so the petals sit relatively
		// further out on small sizes and overlap into a single bloom on large.
		float petal = Math.max(1.6f, s * 0.18f);
		float reach = s * (s < 48 ? 0.30f : 0.19f);
		for (int i = 0; i < 5; i++) {
			double angle = -Math.PI / 2 + i * (2.0 * Math.PI / 5.0);
			int px = cx + (int) Math.round(Math.cos(angle) * reach);
			int py = cy + (int) Math.round(Math.sin(angle) * reach);
			disc(c, px, py, petal, col);
		}
		disc(c, cx, cy, Math.max(1.2f, s * 0.09f), col);
	}


	/**
	 * The client seal: the blossom inside a double ring. Used where the mark has
	 * to read as a badge (the radial hub, the window header) rather than as a
	 * load-bearing decorative flower. At a small size the outer ring is what
	 * makes the shape unique: a plain blossom is a common icon, a sealed one is
	 * not.
	 */
	private static void crest(UiCanvas c, int x, int y, int s, int col) {
		int cx = x + s / 2;
		int cy = y + s / 2;
		int outer = Math.max(3, s / 2 - 1);
		Ui.ring(c, cx, cy, outer, Math.max(1, Math.round(s * 0.055f)), col);
		Ui.ring(c, cx, cy, Math.round(outer * 0.80f), 1, withAlpha(col, 0x70));
		int inset = Math.max(2, Math.round(s * 0.20f));
		blossom(c, x + inset, y + inset, s - inset * 2, col);
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



	// ---- sun: disc with eight rays -----------------------------------------
	private static void sun(UiCanvas c, int x, int y, int s, int col) {
		int cx = x + s / 2;
		int cy = y + s / 2;
		int r = Math.max(2, Math.round(s * 0.20f));
		disc(c, cx, cy, r, col);
		int inner = r + Math.max(1, Math.round(s * 0.10f));
		int outer = Math.max(inner + 1, Math.round(s * 0.46f));
		for (int i = 0; i < 8; i++) {
			double a = i * (Math.PI / 4.0);
			int x0 = cx + (int) Math.round(Math.cos(a) * inner);
			int y0 = cy + (int) Math.round(Math.sin(a) * inner);
			int x1 = cx + (int) Math.round(Math.cos(a) * outer);
			int y1 = cy + (int) Math.round(Math.sin(a) * outer);
			stroke(c, x0, y0, x1, y1, col);
		}
	}

	// ---- rain: cloud with three falling drops --------------------------------
	private static void rain(UiCanvas c, int x, int y, int s, int col) {
		int bottom = Math.round(s * 0.62f);
		cloud(c, x, y, s, bottom, col);
		int cx = x + s / 2;
		int top = y + bottom + Math.max(1, s / 12);
		int len = Math.max(3, s / 4);
		int step = Math.max(3, Math.round(s * 0.24f));
		for (int i = -1; i <= 1; i++) {
			int px = cx + i * step;
			// The middle drop hangs lower, which is what makes the three marks
			// read as falling rain rather than as a dotted underline.
			int start = top + (i == 0 ? Math.max(1, s / 10) : 0);
			stroke(c, px, start, px - 1, start + len, col);
		}
	}

	// ---- thunder: a bolt, heavy enough to read at chip size ------------------
	private static void thunder(UiCanvas c, int x, int y, int s, int col) {
		// A cloud plus a bolt does not survive an eighteen pixel box: both end
		// up as lint. The bolt alone is unmistakable, and the rain icon already
		// owns the cloud.
		int cx = x + s / 2;
		int top = y + 1;
		int h = Math.max(6, s - 2);
		int mid = top + Math.round(h * 0.58f);
		int reach = Math.max(2, Math.round(s * 0.20f));
		thickStroke(c, cx + reach, top, cx - reach, mid, col);
		thickStroke(c, cx - reach, mid, cx + reach, mid, col);
		thickStroke(c, cx + reach, mid, cx - reach, top + h, col);
	}

	// ---- category marks -----------------------------------------------------

	/** Compass: a ring with a two-tone needle. */
	private static void compass(UiCanvas c, int x, int y, int s, int col) {
		int cx = x + s / 2;
		int cy = y + s / 2;
		int r = Math.max(3, s / 2 - 1);
		Ui.ring(c, cx, cy, r, Math.max(1, s / 12), col);
		// Needle: two triangles that share a centre.
		for (int i = 0; i <= s / 2 - 2; i++) {
			int w = Math.max(0, 1 - i / Math.max(1, s / 6));
			c.fill(cx - 1 - w, cy - i, cx + 2 + w, cy - i + 1, col);
			c.fill(cx - 1, cy + i, cx + 2, cy + i + 1, withAlpha(col, 0x99));
		}
	}

	/** Globe: ring, equator and one meridian. */
	private static void globe(UiCanvas c, int x, int y, int s, int col) {
		int cx = x + s / 2;
		int cy = y + s / 2;
		int r = Math.max(3, s / 2 - 1);
		Ui.ring(c, cx, cy, r, Math.max(1, s / 12), col);
		c.fill(cx - r, cy, cx + r + 1, cy + 1, withAlpha(col, 0xCC));
		int meridian = Math.max(2, Math.round(s * 0.18f));
		Ui.ring(c, cx, cy, meridian, 1, col);
	}

	/** Eye: two arcs meeting at the corners, with a pupil. */
	private static void eye(UiCanvas c, int x, int y, int s, int col) {
		int left = x + 1;
		int right = x + s - 2;
		int cy = y + s / 2;
		int steps = Math.max(6, s * 2);
		for (int i = 0; i <= steps; i++) {
			float t = i / (float) steps;
			int px = Math.round(left + (right - left) * t);
			int arc = Math.round((float) Math.sin(t * Math.PI) * (s * 0.30f));
			c.fill(px, cy - arc, px + 1, cy - arc + 1, col);
			c.fill(px, cy + arc, px + 1, cy + arc + 1, col);
		}
		disc(c, cxOf(x, s), cy, Math.max(1.5f, s * 0.13f), col);
	}

	/** Donut: the client's ring in miniature. */
	private static void donut(UiCanvas c, int x, int y, int s, int col) {
		int r = Math.max(3, s / 2 - 1);
		Ui.ring(c, cxOf(x, s), y + s / 2, r, Math.max(2, s / 4), col);
	}

	/** Sliders: three rails with their knobs. */
	private static void sliders(UiCanvas c, int x, int y, int s, int col) {
		int left = x + 1;
		int right = x + s - 2;
		int mid = cxOf(x, s);
		int row0 = y + Math.max(2, s / 4);
		int row1 = y + s / 2;
		int row2 = y + s - Math.max(2, s / 4) - 1;
		c.fill(left, row0, right, row0 + 1, withAlpha(col, 0xAA));
		c.fill(left, row1, right, row1 + 1, col);
		c.fill(left, row2, right, row2 + 1, withAlpha(col, 0xAA));
		int knob = Math.max(2, s / 7);
		c.fill(left + knob + 2, row0 - knob, left + knob + 2 + knob, row0 + knob, col);
		c.fill(right - knob * 2 - 2, row1 - knob, right - knob * 2 - 2 + knob, row1 + knob, col);
		c.fill(mid, row2 - knob, mid + knob, row2 + knob, col);
	}

	/** Layout: a panel with a header band and two content blocks. */
	private static void layout(UiCanvas c, int x, int y, int s, int col) {
		int left = x + 1;
		int top = y + 1;
		int right = x + s - 2;
		int bottom = y + s - 2;
		int mid = cxOf(x, s);
		Ui.ring(c, mid, y + s / 2, Math.max(3, s / 2 - 1), 1, col);
		c.fill(left + 2, top + 2, right - 2, top + 4, col);
		// The two content blocks need two rows even at twelve pixels, where
		// bottom - 3 lands exactly on top + 6 and the shapes are degenerate:
		// the chip then draws as a bare circle with a line through it. The
		// bottom row is therefore never above the top row, and neither block
		// is ever narrower than one pixel for the same reason.
		int blockTop = top + Math.max(5, s / 3);
		int blockBottom = Math.max(blockTop + 2, bottom - 2);
		int leftBlockLeft = left + 3;
		int leftBlockRight = Math.max(leftBlockLeft + 1, mid - 1);
		int rightBlockLeft = mid + 1;
		int rightBlockRight = Math.max(rightBlockLeft + 1, right - 3);
		c.fill(leftBlockLeft, blockTop, leftBlockRight, blockBottom, withAlpha(col, 0xB0));
		c.fill(rightBlockLeft, blockTop, rightBlockRight, blockBottom, withAlpha(col, 0xB0));
	}

	/** Palette: a paint disc with three wells. */
	private static void palette(UiCanvas c, int x, int y, int s, int col) {
		int cx = cxOf(x, s);
		int cy = y + s / 2;
		Ui.ring(c, cx, cy, Math.max(3, s / 2 - 1), Math.max(1, s / 10), col);
		int r = Math.max(1, s / 9);
		c.fill(cx - s / 4, cy - s / 5, cx - s / 4 + r, cy - s / 5 + r, col);
		c.fill(cx + s / 8, cy - s / 5, cx + s / 8 + r, cy - s / 5 + r, col);
		c.fill(cx - s / 4, cy + s / 7, cx - s / 4 + r, cy + s / 7 + r, col);
	}

	/** Back arrow: a left-pointing chevron with a short shaft. */
	private static void back(UiCanvas c, int x, int y, int s, int col) {
		int cx = x + s / 2;
		int cy = y + s / 2;
		int arm = Math.max(2, s / 3);
		stroke(c, cx + arm / 2, cy - arm, cx - arm / 2, cy, col);
		stroke(c, cx - arm / 2, cy, cx + arm / 2, cy + arm, col);
		c.fill(x + 1, cy, cx, cy + 1, col);
	}

	private static int cxOf(int x, int s) {
		return x + s / 2;
	}

	private static int withAlpha(int color, int alpha) {
		return Ui.withAlpha(color, alpha);
	}

	/** A two pixel line, for marks that must survive a small icon box. */
	private static void thickStroke(UiCanvas c, int x0, int y0, int x1, int y1, int col) {
		stroke(c, x0, y0, x1, y1, col);
		stroke(c, x0 + 1, y0, x1 + 1, y1, col);
	}

	/** Three overlapping lobes and a flat base: a cloud at any size. */
	private static void cloud(UiCanvas c, int x, int y, int s, int bottom, int col) {
		int cx = x + s / 2;
		int r = Math.max(2, Math.round(s * 0.15f));
		int baseY = y + bottom;
		disc(c, cx - r, baseY - r, r, col);
		disc(c, cx + r, baseY - r, r, col);
		disc(c, cx, baseY - r - Math.max(1, r / 2), r + 1, col);
		c.fill(cx - r * 2, baseY - r, cx + r * 2, baseY, col);
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

	/**
	 * Filled circle centred on (cx, cy). It goes through {@link Ui#circle} so
	 * the icon picks up the shared edge blending instead of its own staircase.
	 */
	private static void disc(UiCanvas c, int cx, int cy, float radius, int col) {
		Ui.circle(c, cx, cy, Math.max(1, Math.round(radius)), col);
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
