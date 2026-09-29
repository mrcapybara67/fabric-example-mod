package com.griefer.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Reusable on/off switch control. Tracks its own hover + transition state so
 * every module card renders an identical, smoothly animating toggle.
 */
public class UiToggle {
	private final Anim hover = new Anim(0f, 14f);
	private final Anim on = new Anim(0f, 18f);
	private boolean hovered;

	/** Advances hover + transition animation. Call once per frame before render. */
	public void update(boolean isOn, boolean isHovered, float delta) {
		hovered = isHovered;
		hover.to(isHovered ? 1f : 0f, delta);
		on.to(isOn ? 1f : 0f, delta);
	}

	public void render(GuiGraphics g, int x, int y, int w, int h) {
		float t = on.value();
		int track = Ui.lerpColor(UiTheme.SURFACE_2, UiTheme.ACCENT, t);
		if (hovered) {
			track = Ui.lerpColor(track, UiTheme.SURFACE_3, 0.5f * hover.value());
		}
		Ui.roundRect(g, x, y, w, h, UiTheme.RADIUS_SM, track);

		int knob = Ui.lerpColor(UiTheme.TEXT_SUB, 0xFFFFFFFF, t);
		int kw = h - 4;
		float kx = x + 2.0f + t * (w - kw - 4.0f);
		Ui.roundRect(g, Math.round(kx), y + 2, kw, kw, UiTheme.RADIUS_SM, knob);
	}
}
