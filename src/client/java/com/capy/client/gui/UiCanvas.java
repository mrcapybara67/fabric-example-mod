package com.capy.client.gui;

/**
 * A minimal drawing surface. The default methods keep the GUI usable by the
 * offline preview, while {@link McCanvas} replaces them with one tinted
 * nine-slice blit per rounded surface.
 */
public interface UiCanvas {
	void fill(int x1, int y1, int x2, int y2, int argb);

	void pushClip(int x, int y, int width, int height);

	void popClip();

	default int drawCalls() {
		return 0;
	}

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

	default void rounded(int x, int y, int w, int h, int radius, int color) {
		Ui.roundRectFallback(this, x, y, w, h, radius, color);
	}

	/** Rounded surface using the generated sprite for a specific surface kind. */
	default void roundedKind(UiSprites.Kind kind, int x, int y, int w, int h, int radius, int color) {
		rounded(x, y, w, h, radius, color);
	}

	default void roundedRing(int x, int y, int w, int h, int radius, int top, int bottom) {
		Ui.surfaceEdgeFallback(this, x, y, w, h, radius, top, bottom);
	}

	default void softShadow(int x, int y, int w, int h, int radius, int color) {
		Ui.shadowFallback(this, x, y, w, h, radius);
	}

	default void sheen(int x, int y, int w, int h, int radius, float phase) {
		Ui.sheenFallback(this, x, y, w, h, radius, phase);
	}

	default void frost(int x, int y, int w, int h, int color) {
		Ui.frostFallback(this, x, y, w, h, color);
	}

	default void vignette(int width, int height, float fade) {
		Ui.vignetteFallback(this, width, height, fade);
	}

	default void noise(int x, int y, int w, int h, int color) {
		Ui.frostFallback(this, x, y, w, h, color);
	}
}
