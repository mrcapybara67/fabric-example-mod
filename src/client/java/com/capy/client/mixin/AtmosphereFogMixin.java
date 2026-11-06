package com.capy.client.mixin;

import com.capy.client.module.modules.AtmosphereState;
import com.capy.client.util.Guard;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Atmosphere's fog density, applied to the distances vanilla is about to use.
 *
 * <h2>Why the distances and not a colour</h2>
 * Thick fog is a shorter distance, not a darker colour. Tinting the fog makes
 * the far terrain grey while the sky stays clear, which reads as a broken
 * shader; compressing the distances makes the world genuinely fade out at a
 * nearer point, so the horizon, the sky and the terrain agree. The weather, the
 * underwater fog and the blindness effect all end up in the same four numbers,
 * which is why they are scaled together.
 *
 * <h2>Where the numbers are</h2>
 * 1.21.11 no longer returns a fog object: each {@code FogEnvironment} writes
 * into a shared {@link FogData}, which is then uploaded as a buffer. The
 * atmospheric environment is the one that owns the outdoor case; water, lava,
 * powder snow and the effect fogs keep their own distances, because a fog
 * module that changed underwater visibility would be changing a different
 * thing than the one on the card.
 *
 * <h2>Only two of the six fields</h2>
 * The sky and cloud ends are left alone. They bound how far the sky dome and the
 * clouds are drawn, not how far the player can see; scaling them would shrink
 * the sky itself instead of thickening the air in front of it. The colour is a
 * separate hook: a theme owns the hue, while vanilla's light and weather still
 * own the brightness.
 */
@Mixin(AtmosphericFogEnvironment.class)
public class AtmosphereFogMixin {
	/**
	 * Tints the colour half of the fog. Distances decide how thick the air is;
	 * the base colour decides what the air is made of. Both are needed for a
	 * theme to read as a place rather than as a distance slider.
	 */
	@Inject(method = "getBaseColor", at = @At("RETURN"), cancellable = true, require = 0)
	private void capy$tintFog(ClientLevel level, Camera camera, int base, float partialTick,
			CallbackInfoReturnable<Integer> cir) {
		try {
			float hot = AtmosphereState.fogTintHot;
			if (!AtmosphereState.active || hot <= 0.01f) {
				return;
			}
			int original = cir.getReturnValue();
			int a = (original >>> 24) & 0xFF;
			int r = (original >> 16) & 0xFF;
			int g = (original >> 8) & 0xFF;
			int b = original & 0xFF;
			int tr = Math.round(AtmosphereState.fogR * 255f);
			int tg = Math.round(AtmosphereState.fogG * 255f);
			int tb = Math.round(AtmosphereState.fogB * 255f);
			r = Math.round(r + (tr - r) * hot);
			g = Math.round(g + (tg - g) * hot);
			b = Math.round(b + (tb - b) * hot);
			cir.setReturnValue((a << 24) | (r << 16) | (g << 8) | b);
		} catch (Throwable failure) {
			Guard.report("atmosphere fog colour", failure);
		}
	}

	@Inject(method = "setupFog", at = @At("TAIL"), require = 0)
	private void capy$scaleFog(FogData data, Camera camera, ClientLevel level, float partialTick,
			DeltaTracker tracker, CallbackInfo ci) {
		try {
			if (!AtmosphereState.active || data == null) {
				return;
			}
			float hot = AtmosphereState.fogHot;
			if (Math.abs(hot - 1f) < 0.01f) {
				return;
			}
			// A zero or negative multiplier would put the far plane behind the
			// near one and make the world vanish; the slider cannot ask for it,
			// but the clamp also covers a value caught mid-ease.
			float scale = 1f / Math.max(0.05f, hot);
			data.environmentalStart = Math.max(0f, data.environmentalStart * scale);
			data.environmentalEnd = Math.max(data.environmentalStart, data.environmentalEnd * scale);
			data.renderDistanceStart = Math.max(0f, data.renderDistanceStart * scale);
			data.renderDistanceEnd = Math.max(data.renderDistanceStart, data.renderDistanceEnd * scale);
		} catch (Throwable failure) {
			Guard.report("atmosphere fog", failure);
		}
	}
}
