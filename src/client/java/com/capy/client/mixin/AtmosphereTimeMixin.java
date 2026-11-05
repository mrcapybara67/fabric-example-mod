package com.capy.client.mixin;

import com.capy.client.module.modules.AtmosphereState;
import com.capy.client.util.Guard;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Atmosphere's clock and weather, taken where the client reads them.
 *
 * <h2>Time</h2>
 * {@code getDayTime} is what the sun's angle, the sky gradient, the star field
 * and the lightmap's night darkness are all derived from, so overriding it moves
 * the whole celestial picture together. The module keeps its time in the
 * friendly convention (0 = midnight, 0.25 = sunrise), and vanilla counts from
 * sunrise, so the conversion happens here rather than in the setting: "sunrise"
 * should mean what the label says on the card, not what a bytecode comment says.
 *
 * <p>Only the client's own level instances are affected. The server's clock is
 * untouched, no packet is sent, and the bodies still use the real moon phase.
 *
 * <h2>Weather</h2>
 * Rain and thunder are read every frame by the fog, the sky darkening and the
 * lightmap. Overriding the two levels is what makes a forced storm look like a
 * storm rather than like a grey tint: fog thickens, the sky dims, and the light
 * on the ground drops, all through vanilla's own weather path.
 */
@Mixin(Level.class)
public class AtmosphereTimeMixin {
	@Inject(method = "getDayTime", at = @At("RETURN"), cancellable = true, require = 0)
	private void capy$overrideDayTime(CallbackInfoReturnable<Long> cir) {
		try {
			if (!AtmosphereState.active) {
				return;
			}
			float time = AtmosphereState.timeDay;
			if (time < 0f) {
				return;
			}
			// Friendly fraction to vanilla's sunrise-based tick count. The
			// fraction is wrapped first: an eased value is always inside 0..1,
			// but a hand-edited config must not be able to return a negative
			// time and rewind the sky.
			float wrapped = time % 1f;
			if (wrapped < 0f) {
				wrapped += 1f;
			}
			long dayTime = (long) (((wrapped + 0.75f) % 1f) * 24000f);
			cir.setReturnValue(dayTime);
		} catch (Throwable failure) {
			Guard.report("atmosphere time", failure);
		}
	}

	@Inject(method = "getRainLevel", at = @At("RETURN"), cancellable = true, require = 0)
	private void capy$overrideRain(float delta, CallbackInfoReturnable<Float> cir) {
		try {
			if (!AtmosphereState.active) {
				return;
			}
			if (AtmosphereState.rainOvr >= 0f) {
				cir.setReturnValue(AtmosphereState.rainOvr);
			}
		} catch (Throwable failure) {
			Guard.report("atmosphere rain", failure);
		}
	}

	@Inject(method = "getThunderLevel", at = @At("RETURN"), cancellable = true, require = 0)
	private void capy$overrideThunder(float delta, CallbackInfoReturnable<Float> cir) {
		try {
			if (!AtmosphereState.active) {
				return;
			}
			if (AtmosphereState.thunderOvr >= 0f) {
				cir.setReturnValue(AtmosphereState.thunderOvr);
			}
		} catch (Throwable failure) {
			Guard.report("atmosphere thunder", failure);
		}
	}
}
