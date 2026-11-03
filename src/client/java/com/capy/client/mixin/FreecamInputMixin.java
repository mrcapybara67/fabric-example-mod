package com.capy.client.mixin;

import com.capy.client.module.ModuleManager;
import com.capy.client.module.modules.FreecamModule;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the body still while the camera flies.
 *
 * <p>Without this the same WASD keys that fly the camera would walk the player
 * too, and the camera would leave a body that is wandering off behind it.
 * {@code applyInput} is where the local player turns its input into movement,
 * so cancelling it at the head is the narrowest possible freeze: the player
 * still ticks, still animates, still receives damage and still sees the world
 * update — they simply do not move.</p>
 *
 * <p>Guarded like every other hook: a throw here runs inside the player's own
 * tick, which is a crash rather than a moving body.</p>
 */
@Mixin(LocalPlayer.class)
public class FreecamInputMixin {
	@Inject(method = "applyInput", at = @At("HEAD"), cancellable = true)
	private void capy$freezePlayerWhileFlying(CallbackInfo ci) {
		try {
			FreecamModule freecam = ModuleManager.get().get(FreecamModule.class);
			if (freecam != null && freecam.shouldFreezePlayer()) {
				ci.cancel();
			}
		} catch (Throwable failure) {
			com.capy.client.util.Guard.report("freecam freeze", failure);
		}
	}
}
