package com.capy.client.mixin;

import com.capy.client.module.modules.AtmosphereState;
import com.capy.client.util.Guard;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.SkyRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Atmosphere, applied to the sky colour vanilla itself computes.
 *
 * <h2>Why this hook and not a sky mesh</h2>
 * 1.21.11's sky renderer no longer takes a colour argument to draw with: it
 * extracts the frame's sky into a {@link SkyRenderState}, and the render pass
 * that follows reads {@code skyColor} out of it. Tinting the state after
 * extraction therefore tints the same value the sun, the sunrise band and the
 * dark disc are all drawn against — the gradient, the horizon and the bodies
 * stay consistent because there is only one sky colour in the frame.
 *
 * <p>The painted-sky approach this replaces could not do that: it drew an opaque
 * quad over the sky and had to fake the sun and the horizon itself. Here the
 * vanilla sky is still drawn, in its own colours, with ours blended in.
 *
 * <h2>What is not tinted</h2>
 * The sunrise band is left at vanilla's colour, and only moved a little at half
 * strength. A constant tint over a sunset is what makes a sky look like a
 * filter; letting the warm band keep its own hue is what keeps it looking like
 * a sunset.
 */
@Mixin(SkyRenderer.class)
public class AtmosphereSkyMixin {
	@Inject(method = "extractRenderState", at = @At("TAIL"), require = 0)
	private void capy$tintSky(ClientLevel level, float partialTick, Camera camera, SkyRenderState state,
			CallbackInfo ci) {
		try {
			if (!AtmosphereState.active || state == null) {
				return;
			}
			float hot = AtmosphereState.skyHot;
			if (hot <= 0.001f) {
				return;
			}
			state.skyColor = tint(state.skyColor, hot);
			state.sunriseAndSunsetColor = tint(state.sunriseAndSunsetColor, hot * 0.5f);
		} catch (Throwable failure) {
			// A sky tint is never worth a frame: vanilla's colour stays.
			Guard.report("atmosphere sky", failure);
		}
	}

	/**
	 * Blends an ARGB colour towards the atmosphere tint.
	 *
	 * <p>Alpha is kept: the sky state carries opacity, and a tint that also
	 * changed it would make the sky disc flicker between an opaque and a
	 * transparent value rather than simply change hue.</p>
	 */
	private static int tint(int argb, float strength) {
		int a = (argb >>> 24) & 0xFF;
		int r = (argb >> 16) & 0xFF;
		int g = (argb >> 8) & 0xFF;
		int b = argb & 0xFF;
		int tr = Math.round(AtmosphereState.skyR * 255f);
		int tg = Math.round(AtmosphereState.skyG * 255f);
		int tb = Math.round(AtmosphereState.skyB * 255f);
		r = Math.round(r + (tr - r) * strength);
		g = Math.round(g + (tg - g) * strength);
		b = Math.round(b + (tb - b) * strength);
		return (a << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
	}

	private static int clamp(int channel) {
		return Math.max(0, Math.min(255, channel));
	}
}
