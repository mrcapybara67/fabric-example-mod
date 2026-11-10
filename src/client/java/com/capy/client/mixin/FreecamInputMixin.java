package com.capy.client.mixin;

import com.capy.client.module.ModuleManager;
import com.capy.client.module.modules.FreecamModule;
import com.capy.client.util.Guard;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Swaps the body's input object at the start of the local player's tick.
 *
 * <p>The old hook injected into {@code ClientInput.tick}. That method is
 * overridden by {@code KeyboardInput}, so the injector did not run for the input
 * instance the player actually uses. Swapping the object before vanilla reads
 * the keys is reliable: nothing re-reads the keyboard after this point, and the
 * latch sets the snapshot on every tick itself.</p>
 */
@Mixin(LocalPlayer.class)
public abstract class FreecamInputMixin {
	@Inject(method = "tick", at = @At("HEAD"))
	private void capy$swapBodyInput(CallbackInfo ci) {
		try {
			FreecamModule freecam = ModuleManager.get().get(FreecamModule.class);
			if (freecam != null) {
				freecam.beforePlayerTick((LocalPlayer) (Object) this);
			}
		} catch (Throwable failure) {
			Guard.report("freecam input", failure);
		}
	}
}
