package com.capy.client.gui;

/**
 * The switch used by the module cards and by boolean settings.
 *
 * The track is a plum groove; when the setting is on the groove fills with a
 * deep rose and a pale cherry knob slides across. The pink is kept slightly
 * muted on purpose: a row of switches should read as a set of controls, not
 * as a row of neon.
 */
public class UiToggle {
	private final Anim hover = new Anim(0f, 12f);
	private final Anim on = new Anim(0f, 16f);
	private boolean hovered;

	public void update(boolean isOn, boolean isHovered, float delta) {
		hovered = isHovered;
		hover.to(isHovered ? 1f : 0f, delta);
		on.to(isOn ? 1f : 0f, delta);
	}

	public void render(UiCanvas c, int x, int y, int w, int h) {
		float t = on.value();
		int r = h / 2;

		// The groove the knob sits in.
		// The filled track is a muted rose rather than the bright accent: a
		// row of switches should not turn into a row of neon.
		int on = Ui.lerpColor(UiTheme.ACCENT_DEEP, 0xFF3A2030, 0.45f);
		int top = Ui.lerpColor(UiTheme.TRACK_TOP, on, t);
		int bottom = Ui.lerpColor(UiTheme.TRACK_BOTTOM, Ui.lerpColor(on, 0xFF2A1826, 0.5f), t);
		if (t < 0.5f) {
			top = Ui.lerpColor(top, UiTheme.PANEL_ALT, hover.value() * 0.5f);
			bottom = Ui.lerpColor(bottom, UiTheme.PANEL_TOP, hover.value() * 0.5f);
		}
		Ui.surface(c, x, y, w, h, r, top, bottom);
		Ui.surfaceEdge(c, x, y, w, h, r,
				Ui.lerpColor(0x2EFFFFFF, 0x73FFFFFF, t),
				Ui.lerpColor(0x12FFFFFF, 0x26FFFFFF, t));

		// The knob: a pale cherry disc with a soft shadow. It stays smaller
		// than the track so the coloured groove remains visible.
		int d = h - 8;
		int kx = x + 4 + Math.round(t * (w - d - 8));
		int ky = y + 4;
		Ui.roundRect(c, kx + 1, ky + 2, d, d, d / 2, 0x4D000000);
		Ui.surface(c, kx, ky, d, d, d / 2,
				Ui.lerpColor(0xFFD8C4D0, UiTheme.PALE_CHERRY, t),
				Ui.lerpColor(0xFFB9A2B1, 0xFFFFC4D8, t));
		Ui.outline(c, kx, ky, d, d, d / 2, 0x59FFFFFF);
		// A single highlight along the top of the knob.
		Ui.surface(c, kx + 3, ky + 1, d - 6, 1, 0, 0x66FFFFFF);
	}
}
