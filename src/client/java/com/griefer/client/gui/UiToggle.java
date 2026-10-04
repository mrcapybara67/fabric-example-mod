package com.griefer.client.gui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Liquid-glass switch. The track is a translucent pill that picks up the
 * accent as the module turns on, the thumb is a small floating glass disc with
 * its own shadow and highlight. Input is never blocked by animation — the
 * state flips instantly, only the visual eases.
 */
public class UiToggle {
	private final Anim hover = new Anim(0f, 14f);
	private final Anim on = new Anim(0f, 18f);
	private boolean hovered;

	public void update(boolean isOn, boolean isHovered, float delta) {
		hovered = isHovered;
		hover.to(isHovered ? 1f : 0f, delta);
		on.to(isOn ? 1f : 0f, delta);
	}

	public void render(GuiGraphics g, int x, int y, int w, int h) {
		float t = on.value();

		// Enabled: the pill glows from within, so draw the bloom first.
		if (t > 0.02f) {
			Ui.roundRect(g, x - 2, y - 2, w + 4, h + 4, UiTheme.RADIUS_PILL,
					Ui.lerpColor(0x00000000, UiTheme.GLOW_ACCENT, t * 0.55f));
		}

		// Track: dark glass at rest, accent glass when on.
		int top = Ui.lerpColor(UiTheme.TRACK_TOP, UiTheme.ACCENT, t);
		int bottom = Ui.lerpColor(UiTheme.TRACK_BOTTOM, UiTheme.ACCENT_DIM, t);
		if (!hovered && t < 0.5f) {
			top = Ui.lerpColor(top, 0xFF1E2734, hover.value() * 0.5f);
			bottom = Ui.lerpColor(bottom, 0xFF141B26, hover.value() * 0.5f);
		}
		Ui.glassFill(g, x, y, w, h, UiTheme.RADIUS_PILL, top, bottom);
		Ui.glassEdge(g, x, y, w, h, UiTheme.RADIUS_PILL,
				Ui.lerpColor(0x40FFFFFF, 0xB4FFFFFF, t),
				Ui.lerpColor(0x14FFFFFF, 0x3AFFFFFF, t));
		Ui.innerBevel(g, x, y, w, h, UiTheme.RADIUS_PILL,
				Ui.lerpColor(0x22FFFFFF, 0x59FFFFFF, t),
				Ui.lerpColor(0x30000000, 0x1F000000, t));

		// Thumb: a floating glass disc that slides left -> right.
		int d = h - 6;
		int kx = x + 3 + Math.round(t * (w - d - 6));
		int ky = y + 3;
		Ui.roundRect(g, kx + 1, ky + 2, d, d, UiTheme.RADIUS_PILL, 0x50000000);
		int thumbTop = Ui.lerpColor(0xFFF4F8FC, 0xFFFFFFFF, t);
		int thumbBottom = Ui.lerpColor(0xFFC3CEDA, 0xFFE6F6F2, t);
		Ui.glassFill(g, kx, ky, d, d, UiTheme.RADIUS_PILL, thumbTop, thumbBottom);
		Ui.glassEdge(g, kx, ky, d, d, UiTheme.RADIUS_PILL, 0xB4FFFFFF, 0x30FFFFFF);
		Ui.innerBevel(g, kx, ky, d, d, UiTheme.RADIUS_PILL, 0x59FFFFFF, 0x26000000);
	}
}
