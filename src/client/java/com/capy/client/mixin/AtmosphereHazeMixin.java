package com.capy.client.mixin;

import com.capy.client.module.modules.AtmosphereState;
import com.capy.client.util.Guard;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Atmosphere's bloom haze: a soft wash at the edges of the screen, drawn after
 * the HUD.
 *
 * <h2>Why a screen wash at all</h2>
 * The sky, the fog and the light are all world-space, so a bright atmosphere
 * has no way to bloom into the frame the way a camera does. A low-alpha wash
 * that is strongest at the top and bottom edges is the cheapest honest
 * approximation: it does not tint the middle of the screen, so it never fades
 * the world the player is looking at, and it sits above the HUD, so the hotbar
 * and the chips read through it instead of being dimmed by it.
 *
 * <h2>Why a mixin on the HUD</h2>
 * A HUD registry element would be drawn in the order it was registered, which
 * is dependent on the order the modules happened to load. The HUD's own render
 * ends after every element it knows about, which is the one point that is
 * reliably above all of them.
 *
 * <h2>Alpha</h2>
 * The strength is capped at 22% and the colour is the haze tint from the
 * module, so a full-strength haze still leaves the frame readable. The side
 * bars are half of that again: they only exist to stop the wash from ending in
 * a hard vertical line at the top and bottom bands' corners.
 */
@Mixin(Gui.class)
public class AtmosphereHazeMixin {
	/** The strongest alpha the wash may reach, out of 255. */
	private static final float MAX_ALPHA = 0.22f;

	@Inject(method = "render", at = @At("TAIL"), require = 0)
	private void capy$drawHaze(GuiGraphics graphics, DeltaTracker ticker, CallbackInfo ci) {
		try {
			if (!AtmosphereState.active || graphics == null) {
				return;
			}
			float strength = AtmosphereState.fbHot;
			if (strength <= 0.005f) {
				return;
			}
			int width = graphics.guiWidth();
			int height = graphics.guiHeight();
			if (width <= 0 || height <= 0) {
				return;
			}
			int alpha = Math.round(strength * MAX_ALPHA * 255f);
			int r = Math.round(AtmosphereState.hazeR * 255f);
			int g = Math.round(AtmosphereState.hazeG * 255f);
			int b = Math.round(AtmosphereState.hazeB * 255f);
			int band = (clamp(alpha) << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
			// The same colour with the alpha removed: a gradient to "clear".
			int clear = band & 0x00FFFFFF;

			int third = Math.max(1, height / 3);
			graphics.fillGradient(0, 0, width, third, band, clear);
			graphics.fillGradient(0, height - third, width, height, clear, band);

			// The corners the two bands leave uncovered, at half strength, so
			// the wash does not stop in a straight vertical seam.
			int sideAlpha = clamp(alpha / 2);
			int side = (sideAlpha << 24) | (band & 0xFFFFFF);
			int edge = Math.max(1, width / 5);
			graphics.fill(0, 0, edge, height, side);
			graphics.fill(width - edge, 0, width, height, side);
		} catch (Throwable failure) {
			// A failed wash is an invisible wash; the HUD must still draw.
			Guard.report("atmosphere haze", failure);
		}
	}

	private static int clamp(int channel) {
		return Math.max(0, Math.min(255, channel));
	}
}
