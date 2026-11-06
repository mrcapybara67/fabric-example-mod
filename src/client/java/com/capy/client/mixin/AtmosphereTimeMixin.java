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
	/**
	 * True only for the client's own level.
	 *
	 * <p>The mixin is on {@link Level}, and in single player the integrated
	 * server runs in the same JVM with the same transformations applied. Without
	 * this check the module would change the server's clock and weather too: a
	 * world whose daytime is pinned by the client hook stops advancing its own
	 * day counter, and weather reads on the server would follow the client's
	 * forced weather. A client-only feature must ask whose level it is.</p>
	 */
	private boolean capy$isClientLevel() {
		return ((Level) (Object) this).isClientSide();
	}

	@Inject(method = "getDayTime", at = @At("RETURN"), cancellable = true, require = 0)
	private void capy$overrideDayTime(CallbackInfoReturnable<Long> cir) {
		try {
			if (!AtmosphereState.active || !capy$isClientLevel()) {
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
			long withinDay = (long) (((wrapped + 0.75f) % 1f) * 24000f);
			// Keep the day count. getDayTime is not only the time of day: vanilla
			// divides it by 24000 for the HUD day counter and the moon phase. A
			// bare 0..23999 return pinned every world at day one, froze the moon
			// and made the Day chip read "Day 1" forever. Only the fraction inside
			// the current day is replaced.
			Long original = cir.getReturnValue();
			long day = original == null ? 0L : Math.floorDiv(original, 24000L);
			cir.setReturnValue(day * 24000L + withinDay);
		} catch (Throwable failure) {
			Guard.report("atmosphere time", failure);
		}
	}

	@Inject(method = "getRainLevel", at = @At("RETURN"), cancellable = true, require = 0)
	private void capy$overrideRain(float delta, CallbackInfoReturnable<Float> cir) {
		try {
			if (!AtmosphereState.active || !capy$isClientLevel()) {
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
			if (!AtmosphereState.active || !capy$isClientLevel()) {
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
