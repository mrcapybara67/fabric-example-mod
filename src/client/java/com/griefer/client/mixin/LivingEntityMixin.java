package com.griefer.client.mixin;

import com.griefer.client.MinecraftAccessorHolder;
import com.griefer.client.module.ModuleManager;
import com.griefer.client.module.modules.SwingSpeedModule;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Root-cause fix for the Swing Speed module.
 *
 * How vanilla drives the first-person swing animation (verified against the
 * 1.21.11 bytecode):
 *
 *   LivingEntity.updateSwingTime()   // called from Player.aiStep() every tick
 *       duration = getCurrentSwingDuration()
 *       if swinging:  swingTime++; if swingTime >= duration -> reset swing
 *       else:         swingTime = 0
 *       attackAnim = swingTime / duration   // <- what the hand renderer reads
 *
 * The previous mixin overrode {@code getCurrentSwingDuration} — a method only
 * consulted inside {@code updateSwingTime} — and vanilla's own duration math
 * still applied, so the configured value never produced a visibly re-timed
 * animation. Cancelling {@code updateSwingTime} itself and replicating its
 * exact 6-line tick with our duration re-times the animation loop cleanly:
 * no flicker, no desync, and when the module is off vanilla runs untouched.
 *
 * Only the local player is affected — every other entity keeps vanilla behavior.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@Inject(method = "updateSwingTime", at = @At("HEAD"), cancellable = true)
	private void griefer$overrideSwingTime(CallbackInfo ci) {
		SwingSpeedModule module = ModuleManager.get().get(SwingSpeedModule.class);
		if (module == null || !module.isEnabled()) {
			return;
		}
		// Only affect OUR arm — other players'/entities' swings stay vanilla.
		if (MinecraftAccessorHolder.player() != (Object) this) {
			return;
		}

		LivingEntity self = (LivingEntity) (Object) this;
		int duration = module.swingTicks();

		if (self.swinging) {
			self.swingTime++;
			if (self.swingTime >= duration) {
				self.swingTime = 0;
				self.swinging = false;
			}
		} else {
			self.swingTime = 0;
		}
		self.attackAnim = (float) self.swingTime / (float) duration;

		ci.cancel();
	}
}
