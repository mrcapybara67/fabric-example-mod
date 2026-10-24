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

	/**
	 * Filled rounded rectangle.
	 *
	 * The straight bands are drawn once each and only the corner rows are cut
	 * per row, so a corner never covers the middle band twice - a translucent
	 * fill would otherwise be darker along its rounded edges than in its
	 * middle. From a radius of three upwards the corner edge is blended by its
	 * real coverage, which is what removes the stair steps.
	 */
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
		boolean smooth = r >= 3;
		for (int i = 0; i < r; i++) {
			if (smooth) {
				float inset = insetAt(r, i);
				span(c, x + inset, x + r, y + i, color);
				span(c, x + w - r, x + (w - inset), y + i, color);
				span(c, x + inset, x + r, y + h - 1 - i, color);
				span(c, x + w - r, x + (w - inset), y + h - 1 - i, color);
			} else {
				int inset = cornerInset(r, i);
				c.fill(x + inset, y + i, x + r, y + i + 1, color);
				c.fill(x + w - r, y + i, x + w - inset, y + i + 1, color);
				c.fill(x + inset, y + h - 1 - i, x + r, y + h - i, color);
				c.fill(x + w - r, y + h - 1 - i, x + w - inset, y + h - i, color);
			}
		}
	}

	/**
	 * Filled circle.
	 *
	 * Each row is cut at its real half width and the two edge pixels are
	 * blended by their coverage, so the outline reads as a curve instead of a
	 * staircase. Small circles keep the integer path: below a few pixels the
	 * blend costs more than it shows.
	 */
	public static void circle(UiCanvas c, int cx, int cy, int radius, int color) {
		if (radius <= 0) {
			return;
		}
		if (radius < 4) {
			for (int dy = -radius; dy <= radius; dy++) {
				int dx = (int) Math.sqrt((double) radius * radius - (double) dy * dy);
				c.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
			}
			return;
		}
		// Two rows per submission, cut at the narrower of the two so the disc
		// stays inside its radius. At this size the difference between one row
		// and two is not visible, and every badge and switch knob on screen
		// costs half the fills.
		for (int dy = -radius; dy < radius; dy += 2) {
			int rows = Math.min(2, radius - dy);
			float dx = -1f;
			for (int k = 0; k < rows; k++) {
				double half = (double) radius * radius
						- ((double) dy + k + 0.5) * ((double) dy + k + 0.5);
				if (half <= 0) {
					continue;
				}
				float rowDx = (float) Math.sqrt(half);
				dx = dx < 0f ? rowDx : Math.min(dx, rowDx);
			}
			if (dx < 0f) {
				continue;
			}
			spanBand(c, cx - dx, cx + dx, cy + dy, rows, color);
		}
	}

	/**
	 * Fills [left, right) on one row, blending the two end pixels by the part
	 * of each one the span actually covers.
	 *
	 * Everything between the ends is a single fill, so a smooth edge costs at
	 * most three draw calls per row instead of one per pixel.
	 */
	private static void span(UiCanvas c, float left, float right, int y, int color) {
		spanBand(c, left, right, y, 1, color);
	}

	/**
	 * The same span over {@code rows} rows. Used where a shape is built row by
	 * row: two rows can share one submission because the colour and the cut of
	 * a one pixel arc cannot change by a visible amount between them.
	 */
	private static void spanBand(UiCanvas c, float left, float right, int y, int rows, int color) {
		if (right <= left || rows <= 0) {
			return;
		}
		int alpha = color >>> 24;
		if (alpha <= 0) {
			return;
		}
		int rgb = color & 0x00FFFFFF;
		int start = (int) Math.ceil(left);
		int end = (int) Math.floor(right);
		if (end <= start) {
			int blended = Math.round(alpha * Math.min(1f, right - left));
			if (blended > 0) {
				int px = (int) Math.floor(left);
				c.fill(px, y, px + 1, y + rows, (blended << 24) | rgb);
			}
			return;
		}
		c.fill(start, y, end, y + rows, color);
		int leftEdge = start - 1;
		if (leftEdge >= (int) Math.floor(left)) {
			int blended = Math.round(alpha * (start - left));
			if (blended > 0) {
				c.fill(leftEdge, y, leftEdge + 1, y + rows, (blended << 24) | rgb);
			}
		}
		if (right > end) {
			int blended = Math.round(alpha * (right - end));
			if (blended > 0) {
				c.fill(end, y, end + 1, y + rows, (blended << 24) | rgb);
			}
		}
	}

	/**
	 * The horizontal inset of a rounded corner at the centre of {@code row},
	 * with the fraction of a pixel kept. The rows in the middle of a shape are
	 * not affected, so this returns zero outside the corner.
	 */
	private static float insetAt(int radius, int row) {
		if (radius <= 0 || row >= radius) {
			return 0f;
		}
		double dy = radius - (row + 0.5);
		double squared = (double) radius * radius - dy * dy;
		return (float) (radius - Math.sqrt(Math.max(0.0, squared)));
	}	/**
	 * The horizontal inset of a rounded corner at row {@code i} measured from
	 * the top of the shape, rounded to a whole pixel. Used to clip or mask a
	 * square image into a rounded shape.
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
		if (topColor == bottomColor || h <= 1) {
			// Flat surfaces do not need a gradient at all, and the rounded
			// rectangle path is cheaper than one fill per row.
			roundRect(c, x, y, w, h, r, topColor);
			return;
		}
		if (r <= 1 || h <= r * 2 + 1) {
			// Too short for a gradient band to exist between the corners.
			rowSurface(c, x, y, w, h, r, topColor, bottomColor);
			return;
		}

		// Only the corner rows need per-row widths; the whole middle is one
		// gradient quad. A panel used to cost one submission per row, which is
		// what made the interface lag on a large window.
		int last = h - 1;
		for (int i = 0; i < r; i++) {
			int inset = cornerInset(r, i);
			c.fill(x + inset, y + i, x + w - inset, y + i + 1,
					lerpColor(topColor, bottomColor, i / (float) last));
			int lower = last - i;
			c.fill(x + inset, y + lower, x + w - inset, y + lower + 1,
					lerpColor(topColor, bottomColor, lower / (float) last));
		}
		c.gradient(x, y + r, x + w, y + h - r,
				lerpColor(topColor, bottomColor, r / (float) last),
				lerpColor(topColor, bottomColor, (last - r) / (float) last));
	}

	/** The row by row fallback, used only by surfaces shorter than their corners. */
	private static void rowSurface(UiCanvas c, int x, int y, int w, int h, int r, int topColor, int bottomColor) {
		boolean smooth = r >= 3;
		int last = Math.max(1, h - 1);
		// This path is used by short surfaces - pills, chips, the toggle track -
		// where the corner bands meet in the middle and every row genuinely
		// differs in width. Two rows share one submission: the colour of a band
		// this small differs by less than one step and the cut of the arc by a
		// pixel at most, which is invisible, while the count of submissions for
		// every chip on screen is halved.
		int step = h >= 10 ? 2 : 1;
		for (int i = 0; i < h; i += step) {
			int rows = Math.min(step, h - i);
			int color = lerpColor(topColor, bottomColor,
					Math.min(i + (rows - 1) * 0.5f, last) / (float) last);
			float inset = 0f;
			for (int k = 0; k < rows; k++) {
				int row = i + k;
				float rowInset = smooth
						? Math.max(insetAt(r, row), insetAt(r, h - 1 - row))
						: Math.max(cornerInset(r, row), cornerInset(r, h - 1 - row));
				inset = Math.max(inset, rowInset);
			}
			if (inset <= 0.003f) {
				c.fill(x, y + i, x + w, y + i + rows, color);
			} else {
				spanBand(c, x + inset, x + w - inset, y + i, rows, color);
			}
		}
	}

	/** Flat rounded rectangle. */
	public static void surface(UiCanvas c, int x, int y, int w, int h, int radius, int color) {
		roundRect(c, x, y, w, h, radius, color);
	}

	/**
	 * One pixel rim whose colour is interpolated from top to bottom.
	 *
	 * The rim is drawn as four straight edges plus the corner arcs instead of
	 * one fill per row. A panel that is five hundred pixels tall therefore
	 * costs about ninety fills instead of a thousand, which matters because
	 * the GUI redraws the shell every frame.
	 */
	public static void surfaceEdge(UiCanvas c, int x, int y, int w, int h, int radius, int topColor, int bottomColor) {
		if (w <= 0 || h <= 0) {
			return;
		}
		int r = clampRadius(radius, w, h);
		// The outermost row of a corner is cut the most, and the top and the
		// bottom row of a rounded rectangle are cut the same way.
		int edgeInset = r <= 0 ? 0 : cornerInset(r, 0);

		// The straight edges.
		c.fill(x + edgeInset, y, x + w - edgeInset, y + 1, topColor);
		c.fill(x + edgeInset, y + h - 1, x + w - edgeInset, y + h, bottomColor);
		drawColumn(c, x, y + edgeInset, h - edgeInset * 2, topColor, bottomColor);
		drawColumn(c, x + w - 1, y + edgeInset, h - edgeInset * 2, topColor, bottomColor);

		// The corner arcs, mirrored top and bottom. From a radius of three the
		// arc is drawn at its real position and blended over the two pixels it
		// straddles, which is what turns a staircase into a curve.
		boolean smooth = r >= 3;
		// Two corner rows share one rasterised step. The arc is one pixel wide
		// and a soft colour; at this size the pairing is invisible, and it
		// halves the submissions of every panel outline in the window.
		int step = smooth ? 2 : 1;
		for (int i = 0; i < r; i += step) {
			int rows = Math.min(step, r - i);
			int rowTop = y + i;
			int rowBottom = y + h - i - rows;
			int colorTop = lerpColor(topColor, bottomColor, i / (float) Math.max(1, h - 1));
			int colorBottom = lerpColor(topColor, bottomColor, (h - 1 - i) / (float) Math.max(1, h - 1));
			if (smooth) {
				float inset = insetAt(r, i);
				edgePixelBand(c, x + inset, rowTop, rows, colorTop);
				edgePixelBand(c, x + w - inset - 1, rowTop, rows, colorTop);
				edgePixelBand(c, x + inset, rowBottom, rows, colorBottom);
				edgePixelBand(c, x + w - inset - 1, rowBottom, rows, colorBottom);
			} else {
				int inset = cornerInset(r, i);
				c.fill(x + inset, rowTop, x + inset + 1, rowTop + rows, colorTop);
				c.fill(x + w - 1 - inset, rowTop, x + w - inset, rowTop + rows, colorTop);
				c.fill(x + inset, rowBottom, x + inset + 1, rowBottom + rows, colorBottom);
				c.fill(x + w - 1 - inset, rowBottom, x + w - inset, rowBottom + rows, colorBottom);
			}
		}
	}

	/**
	 * One pixel of a one pixel wide vertical line that starts at a fractional
	 * x. The line is split over the two pixels it actually covers, so an arc
	 * made of these reads as a curve instead of a staircase.
	 */
	private static void edgePixelBand(UiCanvas c, float x, int y, int height, int color) {
		int alpha = color >>> 24;
		if (alpha <= 0 || height <= 0) {
			return;
		}
		int rgb = color & 0x00FFFFFF;
		int base = (int) Math.floor(x);
		float fraction = x - base;
		int left = Math.round(alpha * (1f - fraction));
		int right = Math.round(alpha * fraction);
		if (left > 0) {
			c.fill(base, y, base + 1, y + height, (left << 24) | rgb);
		}
		if (right > 0) {
			c.fill(base + 1, y, base + 2, y + height, (right << 24) | rgb);
		}
	}

	/** A vertical line split into four segments so the rim can fade. */
	private static void drawColumn(UiCanvas c, int x, int y, int height, int topColor, int bottomColor) {
		if (height <= 0) {
			return;
		}
		int quarter = height / 4;
		if (quarter <= 0) {
			c.fill(x, y, x + 1, y + height, topColor);
			return;
		}
		for (int i = 0; i < 4; i++) {
			int segY = y + i * quarter;
			int segH = (i == 3) ? (y + height) - segY : quarter;
			float t = (i * quarter) / (float) Math.max(1, height - 1);
			c.fill(x, segY, x + 1, segY + segH, lerpColor(topColor, bottomColor, t));
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

	/**
	 * Layered drop shadow under a floating panel.
	 *
	 * The layers are almost transparent and deliberately soft, so they use the
	 * plain corner path: blending their edge would cost draw calls that cannot
	 * be seen through a shadow this faint.
	 */
	public static void shadow(UiCanvas c, int x, int y, int w, int h, int radius) {
		roundRectSharp(c, x + 4, y + 10, w, h, radius + 4, UiTheme.SHADOW_WIDE);
		roundRectSharp(c, x + 2, y + 5, w, h, radius + 2, UiTheme.SHADOW_MID);
		roundRectSharp(c, x + 1, y + 2, w, h, radius + 1, UiTheme.SHADOW_TIGHT);
	}

	/** The same shape without edge blending, for fills that do not need it. */
	private static void roundRectSharp(UiCanvas c, int x, int y, int w, int h, int radius, int color) {
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
		// Shadows are soft and almost transparent, so their corners can be
		// stepped in two pixel bands without any visible difference.
		for (int i = 0; i < r; i += 2) {
			int inset = cornerInset(r, i);
			int band = Math.min(2, r - i);
			c.fill(x + inset, y + i, x + r, y + i + band, color);
			c.fill(x + w - r, y + i, x + w - inset, y + i + band, color);
			c.fill(x + inset, y + h - i - band, x + r, y + h - i, color);
			c.fill(x + w - r, y + h - i - band, x + w - inset, y + h - i, color);
		}
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
		for (int col = from; col <= to; col += 2) {
			float d = (col + 0.5f - center) / halfWidth;
			if (d <= -1f || d >= 1f) {
				continue;
			}
			float a = 1f - d * d;
			int alpha = Math.round(0x12 * a * a);
			if (alpha <= 0) {
				continue;
			}
			c.fill(ix + col, iy, ix + col + 2, iy + ih, (alpha << 24) | 0x00FFFFFF);
		}
		c.popClip();
	}

	/** Horizontal hairline with a soft fade at both ends. */
	public static void fadeLine(UiCanvas c, int x, int y, int w, int color) {
		if (w <= 0) {
			return;
		}
		int edge = Math.min(w / 2, 12);
		int baseAlpha = color >>> 24;
		int rgb = color & 0x00FFFFFF;

		// The flat middle is a single fill and only the two short ramps are
		// drawn per pixel. It is the same picture as before, but a 500 pixel
		// rule now costs 41 fills instead of 500 - and it is drawn every frame.
		int midStart = x + edge;
		int midEnd = x + w - edge;
		if (midEnd > midStart && baseAlpha > 0) {
			c.fill(midStart, y, midEnd, y + 1, (baseAlpha << 24) | rgb);
		}
		for (int i = 0; i < edge; i++) {
			int a = baseAlpha * i / edge;
			if (a <= 0) {
				continue;
			}
			int column = (a << 24) | rgb;
			c.fill(x + i, y, x + i + 1, y + 1, column);
			c.fill(x + w - 1 - i, y, x + w - i, y + 1, column);
		}
	}

	/** A vertical gradient band with a soft horizontal falloff, used for glows. */
	public static void glow(UiCanvas c, int x, int y, int w, int h, int color, float strength) {
		int alpha = Math.round(((color >>> 24) & 0xFF) * strength);
		if (alpha <= 0) {
			return;
		}
		int rgb = color & 0x00FFFFFF;
		// The falloff is a smooth curve; a band every few rows samples it
		// closely enough to be indistinguishable and keeps a full window glow
		// at a few dozen submissions instead of hundreds.
		int step = Math.max(1, h / 32);
		for (int i = 0; i < h; i += step) {
			int rows = Math.min(step, h - i);
			float t = h <= 1 ? 0.5f : (i + rows * 0.5f) / (float) h;
			float falloff = (float) Math.sin(t * Math.PI);
			int a = Math.round(alpha * falloff);
			if (a <= 0) {
				continue;
			}
			c.fill(x, y + i, x + w, y + i + rows, (a << 24) | rgb);
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
			// A string is never narrower than its own prefix, so the cut can be
			// binary searched. The old loop re-measured the whole string once
			// per removed character, which is quadratic in the label length and
			// ran for every truncated label on every frame.
			int low = 0;
			int high = s.length();
			while (low < high) {
				int mid = (low + high + 1) >>> 1;
				if (font.width(s.substring(0, mid) + ell) > maxWidth) {
					high = mid - 1;
				} else {
					low = mid;
				}
			}
			s = s.substring(0, Math.max(1, low)) + ell;
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

	/**
	 * The y a line of text must be drawn at to sit vertically centred inside a
	 * box of the given height.
	 *
	 * Every label, value, hint and caption in the interface goes through this
	 * one rule instead of a hand tuned offset. Hand tuned offsets were the
	 * reason text on the same row drifted apart: each font has its own line
	 * height, so "+2" happened to look right for one face and was wrong for
	 * the next.
	 */
	public static int centerTextY(int boxTop, int boxHeight, UiText font) {
		if (font == null) {
			return boxTop;
		}
		return boxTop + (boxHeight - font.lineHeight()) / 2;
	}

	/** The y that puts the bottom of a line of text {@code gap} above boxBottom. */
	public static int textYFromBottom(int boxBottom, int gap, UiText font) {
		int lineHeight = font == null ? 9 : font.lineHeight();
		return boxBottom - gap - lineHeight;
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
