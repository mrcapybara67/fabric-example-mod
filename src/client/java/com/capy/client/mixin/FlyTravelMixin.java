package com.capy.client.mixin;

import com.capy.client.module.modules.FlyModule;
import com.capy.client.util.Guard;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives {@link FlyModule} vanilla's one movement entry point.
 *
 * <p>{@code LivingEntity.travel} is where gravity, drag and the movement keys
 * become a displacement for every living entity. The module replaces it for
 * the local player only and only while it is on; every other entity, and the
 * player with the module off, runs the method untouched. The module does its
 * own collision-resolved move, so walls behave exactly as they always have.</p>
 */
@Mixin(LivingEntity.class)
public abstract class FlyTravelMixin {
	@Inject(method = "travel", at = @At("HEAD"), cancellable = true)
	private void capy$flyTravel(Vec3 input, CallbackInfo ci) {
		// The cast goes through Object because a mixin class does not inherit
		// from its target, so `this` is not statically a LivingEntity either.
		if (!((Object) this instanceof LocalPlayer player)) {
			return;
		}
		try {
			FlyModule module = FlyModule.active();
			if (module == null || !module.overridesTravel(player)) {
				return;
			}
			module.fly(player);
			ci.cancel();
		} catch (Throwable failure) {
			// A broken flight must fall back to vanilla movement, never take
			// the world tick down with it.
			Guard.report("fly", failure);
		}
	}
}
