package com.capy.client.gui;


/**
 * A small tooltip that fades in after a short hover delay.
 *
 * It is drawn last so it floats above every panel, and it never covers the
 * pointer itself: it is placed next to the region that triggered it.
 */
public final class UiTooltip {
	/** Delay before the tooltip appears, in milliseconds. */
	private static final long DELAY_MS = 350L;
	/** Time the tooltip takes to fade in. */
	private static final long FADE_MS = 140L;

	private String text = "";
	private long shownAt;
	private int x;
	private int y;
	private boolean visible;

	/** Shows the tooltip at the given anchor, resetting the fade. */
	public void show(String text, int x, int y) {
		if (!text.equals(this.text)) {
			this.text = text;
			this.shownAt = System.currentTimeMillis();
		}
		this.x = x;
		this.y = y;
		this.visible = true;
	}

	/** Hides the tooltip. */
	public void hide() {
		visible = false;
	}

	/** Draws the tooltip when it is due. */
	public void render(UiCanvas c, UiText font) {
		if (!visible || text.isEmpty()) {
			return;
		}
		long elapsed = System.currentTimeMillis() - shownAt;
		if (elapsed < DELAY_MS) {
			return;
		}
		float alpha = Math.min(1f, (elapsed - DELAY_MS) / (float) FADE_MS);
		int alphaInt = Math.round(255 * alpha);
		if (alphaInt <= 0) {
			return;
		}

		int pad = 6;
		int w = font.width(text) + pad * 2;
		int h = font.lineHeight() + pad * 2 - 2;
		int bx = x;
		int by = y - h - 4;
		if (by < 4) {
			by = y + 12;
		}

		Ui.shadow(c, bx, by, w, h, UiTheme.RADIUS);
		Ui.surface(c, bx, by, w, h, UiTheme.RADIUS,
				Ui.withAlpha(UiTheme.PANEL_ALT, alphaInt),
				Ui.withAlpha(UiTheme.PANEL_TOP, alphaInt));
		Ui.outline(c, bx, by, w, h, UiTheme.RADIUS, Ui.withAlpha(UiTheme.BORDER, alphaInt));
		font.draw(c, text, bx + pad, by + pad - 1, Ui.withAlpha(UiTheme.TEXT, alphaInt));
	}
}
