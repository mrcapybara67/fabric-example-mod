package com.griefer.client.gui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Modern pill-shaped toggle switch. Smoothly sliding thumb, accent-colored
 * track when enabled, subtle hover tint when off. Input is never blocked by
 * animation — the state flips instantly, only the visual eases.
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

		int track = Ui.lerpColor(UiTheme.SURFACE_3, UiTheme.ACCENT, t);
		if (hovered && t < 0.5f) {
			track = Ui.lerpColor(track, 0xFF2C3642, hover.value() * 0.8f);
		}
		Ui.roundRect(g, x, y, w, h, UiTheme.RADIUS_PILL, track);

		// Thumb: full-height circle, slides left -> right
		int d = h - 2;
		int kx = x + 1 + Math.round(t * (w - d - 2));
		int ky = y + 1;
		Ui.roundRect(g, kx, ky, d, d, UiTheme.RADIUS_PILL, 0xFFFFFFFF);
	}
}
