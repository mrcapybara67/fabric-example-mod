package com.capy.client.module;

/**
 * A three-way choice that decides which layers of a box are drawn:
 * {@code lines} (outline only), {@code fill} (translucent faces only) or
 * {@code both}.
 *
 * <p>The renderer asks {@link #fills()} and {@link #outlines()} instead of
 * comparing strings, so the module and the drawing code cannot disagree about
 * what "both" means.</p>
 */
public final class ShapeSetting extends ChoiceSetting {
	public static final String LINES = "lines";
	public static final String FILL = "fill";
	public static final String BOTH = "both";

	public ShapeSetting(String key, String defaultMode) {
		super(key, indexOf(defaultMode), LINES, FILL, BOTH);
	}

	private static int indexOf(String mode) {
		if (FILL.equals(mode)) {
			return 1;
		}
		if (LINES.equals(mode)) {
			return 0;
		}
		return 2;
	}

	/** True when translucent faces are drawn. */
	public boolean fills() {
		return is(FILL) || is(BOTH);
	}

	/** True when the twelve edges are drawn. */
	public boolean outlines() {
		return is(LINES) || is(BOTH);
	}
}
