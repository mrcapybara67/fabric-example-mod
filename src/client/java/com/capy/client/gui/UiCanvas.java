package com.capy.client.gui;

/**
 * A minimal drawing surface.
 *
 * The GUI only ever needs to fill axis aligned rectangles and to clip them, so
 * that is exactly what this interface exposes. Keeping the surface this small
 * has two benefits:
 *
 *   <ul>
 *     <li>the drawing code is independent of the rendering pipeline, so the
 *         same code can be rasterised offline for visual review;</li>
 *     <li>every component is forced to compose from flat fills, which is what
 *         keeps the interface crisp at any GUI scale.</li>
 *   </ul>
 *
 * Colours are plain ARGB ints. Alpha is composited by the implementation, so a
 * canvas may be asked to draw translucent layers over opaque ones.
 */
public interface UiCanvas {
	/** Fills the rectangle (x1, y1) to (x2, y2) exclusive with the given colour. */
	void fill(int x1, int y1, int x2, int y2, int argb);

	/**
	 * Restricts drawing to the given rectangle until the matching
	 * {@link #popClip()}. Nested clips intersect.
	 */
	void pushClip(int x, int y, int width, int height);

	/** Ends the most recent clip. */
	void popClip();

	/**
	 * Fills the rectangle with a vertical gradient from {@code top} at
	 * {@code y1} to {@code bottom} at {@code y2}.
	 *
	 * It is a method of its own because a real canvas can draw it as one quad
	 * with two vertex colours. Building the same gradient out of one fill per
	 * row is what made the interface expensive: a five hundred pixel panel
	 * became five hundred renderer submissions every frame. The default is the
	 * row by row version, so an implementation that cannot do better still
	 * renders correctly.
	 */
	default void gradient(int x1, int y1, int x2, int y2, int top, int bottom) {
		int height = y2 - y1;
		if (x2 <= x1 || height <= 0) {
			return;
		}
		if (top == bottom) {
			fill(x1, y1, x2, y2, top);
			return;
		}
		for (int i = 0; i < height; i++) {
			float t = height == 1 ? 0f : i / (float) (height - 1);
			fill(x1, y1 + i, x2, y1 + i + 1, Ui.lerpColor(top, bottom, t));
		}
	}
}
