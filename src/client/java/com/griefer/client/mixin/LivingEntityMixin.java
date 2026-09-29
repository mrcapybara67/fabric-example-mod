package com.griefer.client.mixin;

import com.griefer.client.MinecraftAccessorHolder;
import com.griefer.client.module.ModuleManager;
import com.griefer.client.module.modules.SwingSpeedModule;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla computes the arm swing duration in
 * LivingEntity#getCurrentSwingDuration():
 * - mining fatigue: base + (amplifier + 1) * 2 ticks
 * - otherwise: the item's SwingAnimation duration (6 ticks by default)
 *
 * This only changes the VISUAL animation speed of the local player.
 * Attack cooldown and mining speed are server-side and remain vanilla.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@Inject(method = "getCurrentSwingDuration", at = @At("HEAD"), cancellable = true)
	private void griefer$overrideSwingDuration(CallbackInfoReturnable<Integer> cir) {
		SwingSpeedModule module = ModuleManager.get().get(SwingSpeedModule.class);
		if (module == null || !module.isEnabled()) {
			return;
		}
		// Only affect OUR arm — other players' swings stay vanilla.
		if (MinecraftAccessorHolder.player() != (Object) this) {
			return;
		}
		cir.setReturnValue(module.swingTicks());
	}
}
