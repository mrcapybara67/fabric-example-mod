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
}
