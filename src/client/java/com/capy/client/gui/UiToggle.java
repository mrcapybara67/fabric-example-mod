package com.capy.client.gui;

/**
 * The switch used by the module cards and by boolean settings.
 *
 * The track is a cool groove; when the setting is on the groove fills with
 * the glass accent and a pale blue knob slides across. One accent for every
 * control, so a row of switches reads as one system rather than a set of
 * unrelated colours.
 */
public class UiToggle {
	private final Anim hover = new Anim(0f, 12f);
	private final Anim on = new Anim(0f, 10f);
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
		int on = Ui.lerpColor(UiTheme.ACCENT_DEEP, UiTheme.ACCENT, 0.35f);
		int top = Ui.lerpColor(UiTheme.TRACK_TOP, on, t);
		int bottom = Ui.lerpColor(UiTheme.TRACK_BOTTOM, UiTheme.ACCENT_DEEP, t);
		if (t < 0.5f) {
			top = Ui.lerpColor(top, UiTheme.PANEL_ALT, hover.value() * 0.5f);
			bottom = Ui.lerpColor(bottom, UiTheme.PANEL_TOP, hover.value() * 0.5f);
		}
		Ui.surfaceKind(c, UiSprites.Kind.TOGGLE_TRACK, x, y, w, h, r,
				Ui.lerpColor(top, bottom, 0.5f));
		Ui.surfaceEdge(c, x, y, w, h, r,
				Ui.lerpColor(0x2EFFFFFF, 0x73FFFFFF, t),
				Ui.lerpColor(0x12FFFFFF, 0x26FFFFFF, t));

		// The knob: a pale cherry disc with a soft shadow. It stays smaller
		// than the track so the coloured groove remains visible.
		int d = h - 8;
		// A short overshoot on the knob: the track crossfades underneath while
		// the knob arrives with a little spring, not a linear slide.
		float knobT = Math.max(0f, Math.min(1f, Motion.easeOutBack(t, 1.35f)));
		int kx = x + 4 + Math.round(knobT * (w - d - 8));
		int ky = y + 4;
		Ui.roundRect(c, kx + 1, ky + 2, d, d, d / 2, 0x4D000000);
		Ui.surfaceKind(c, UiSprites.Kind.TOGGLE_KNOB, kx, ky, d, d, d / 2,
				Ui.lerpColor(Ui.lerpColor(0xFFEAF4FC, UiTheme.ACCENT_2, t),
						Ui.lerpColor(0xFFC4D7E8, UiTheme.ACCENT, t), 0.5f));
		Ui.outline(c, kx, ky, d, d, d / 2, 0x59FFFFFF);
		// A single highlight along the top of the knob.
		Ui.surface(c, kx + 3, ky + 1, d - 6, 1, 0, 0x66FFFFFF);
	}
}
