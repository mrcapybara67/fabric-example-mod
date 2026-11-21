package com.capy.client.gui;

/**
 * The switch used by the module cards and by boolean settings.
 *
 * <p>It is deliberately blocky: a squared groove and a square knob, not a
 * pill. The shape is part of the ClickGUI's visual language, and it makes the
 * on/off state easier to read at a glance on a dense card grid.</p>
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
		int r = 2;

		// The squared groove the knob sits in.
		int active = Ui.lerpColor(UiTheme.ACCENT_DEEP, UiTheme.ACCENT, 0.35f);
		int top = Ui.lerpColor(UiTheme.TRACK_TOP, active, t);
		int bottom = Ui.lerpColor(UiTheme.TRACK_BOTTOM, UiTheme.ACCENT_DEEP, t);
		if (t < 0.5f) {
			top = Ui.lerpColor(top, UiTheme.PANEL_ALT, hover.value() * 0.5f);
			bottom = Ui.lerpColor(bottom, UiTheme.PANEL_TOP, hover.value() * 0.5f);
		}
		Ui.roundRect(c, x + 1, y + 2, w, h, r, 0x33000000);
		Ui.surfaceKind(c, UiSprites.Kind.TOGGLE_TRACK, x, y, w, h, r,
				Ui.lerpColor(top, bottom, 0.5f));
		Ui.surfaceEdge(c, x, y, w, h, r,
				Ui.lerpColor(0x2EFFFFFF, 0x73FFFFFF, t),
				Ui.lerpColor(0x12FFFFFF, 0x26FFFFFF, t));

		// The knob and the track fill carry the state on their own. The old
		// pair of track ticks looked like a pause icon once the knob slid right,
		// and added noise to every card in the grid.
		int d = h - 8;
		// A short overshoot on the knob: the track crossfades underneath while
		// the knob arrives with a little spring, not a linear slide.
		float knobT = Math.max(0f, Math.min(1f, Motion.easeOutBack(t, 1.35f)));
		int kx = x + 4 + Math.round(knobT * (w - d - 8));
		int ky = y + 4;
		Ui.roundRect(c, kx + 1, ky + 2, d, d, 1, 0x4D000000);
		Ui.surfaceKind(c, UiSprites.Kind.TOGGLE_KNOB, kx, ky, d, d, 1,
				Ui.lerpColor(Ui.lerpColor(0xFFEAF4FC, UiTheme.ACCENT_2, t),
						Ui.lerpColor(0xFFC4D7E8, UiTheme.ACCENT, t), 0.5f));
		Ui.outline(c, kx, ky, d, d, 1, 0x73FFFFFF);
	}
}
