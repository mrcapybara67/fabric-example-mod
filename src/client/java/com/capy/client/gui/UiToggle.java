package com.capy.client.gui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Sakura switch: a plum track that fills with the accent when the setting is
 * on, and a light disc that slides from left to right.
 *
 * The state flips instantly on click; only the visual eases. The knob is
 * drawn as a two pixel "petal" (a slightly squashed disc) so the control
 * matches the blossom motif without turning into a cartoon.
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

		// Enabled: the track blooms softly from within.
		if (t > 0.02f) {
			Ui.roundRect(g, x - 2, y - 2, w + 4, h + 4, UiTheme.RADIUS_PILL,
					Ui.lerpColor(0x00000000, UiTheme.ACCENT_GLOW, t * 0.9f));
		}

		// Track: dark plum at rest, accent gradient when on.
		int top = Ui.lerpColor(UiTheme.TRACK_TOP, UiTheme.ACCENT, t);
		int bottom = Ui.lerpColor(UiTheme.TRACK_BOTTOM, UiTheme.ACCENT_DEEP, t);
		if (t < 0.5f) {
			top = Ui.lerpColor(top, UiTheme.PANEL_ALT, hover.value() * 0.55f);
			bottom = Ui.lerpColor(bottom, UiTheme.PANEL_TOP, hover.value() * 0.55f);
		}
		Ui.surface(g, x, y, w, h, UiTheme.RADIUS_PILL, top, bottom);
		Ui.surfaceEdge(g, x, y, w, h, UiTheme.RADIUS_PILL,
				Ui.lerpColor(0x38FFFFFF, 0xB4FFFFFF, t),
				Ui.lerpColor(0x12FFFFFF, 0x38FFFFFF, t));

		// Knob: a pale disc that glides across the track.
		int d = h - 6;
		int kx = x + 3 + Math.round(t * (w - d - 6));
		int ky = y + 3;
		Ui.roundRect(g, kx + 1, ky + 2, d, d, UiTheme.RADIUS_PILL, 0x59000000);
		int knobTop = Ui.lerpColor(0xFFF3E7EE, 0xFFFFF6FA, t);
		int knobBottom = Ui.lerpColor(0xFFC9B4C1, 0xFFFFC4D8, t);
		Ui.surface(g, kx, ky, d, d, UiTheme.RADIUS_PILL, knobTop, knobBottom);
		Ui.outline(g, kx, ky, d, d, UiTheme.RADIUS_PILL, 0x66FFFFFF);
	}
}
