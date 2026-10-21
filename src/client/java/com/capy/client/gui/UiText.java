package com.capy.client.gui;

/**
 * A typeface the GUI can measure and draw with.
 *
 * The GUI needs four weights and exact text measurement for ellipsis, right
 * alignment and centring, so those are the only two operations exposed.
 */
public interface UiText {
	/** The width of the given string in pixels. */
	int width(String text);

	/** The height of one line, including leading. */
	int lineHeight();

	/**
	 * Draws the string with its top-left corner at (x, y) on the given canvas.
	 *
	 * The canvas is passed in so a typeface can be a single reusable instance
	 * instead of one object per frame.
	 */
	void draw(UiCanvas canvas, String text, int x, int y, int argb);
}
