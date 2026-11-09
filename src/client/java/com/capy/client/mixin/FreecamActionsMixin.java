package com.capy.client.mixin;

import com.capy.client.module.ModuleManager;
import com.capy.client.module.modules.FreecamModule;
import com.capy.client.util.Guard;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keys that are flying the camera must not also act on the world. */
@Mixin(Minecraft.class)
public abstract class FreecamActionsMixin {
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void capy$freecamAttack(CallbackInfoReturnable<Boolean> cir) {
		if (capy$freecamActive()) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void capy$freecamUse(CallbackInfo ci) {
		if (capy$freecamActive()) {
			ci.cancel();
		}
	}

	@Inject(method = "pickBlock", at = @At("HEAD"), cancellable = true)
	private void capy$freecamPick(CallbackInfo ci) {
		if (capy$freecamActive()) {
			ci.cancel();
		}
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void capy$freecamContinueAttack(boolean leftClick, CallbackInfo ci) {
		if (capy$freecamActive()) {
			ci.cancel();
		}
	}

	private static boolean capy$freecamActive() {
		try {
			FreecamModule freecam = ModuleManager.get().get(FreecamModule.class);
			return freecam != null && freecam.isEnabled();
		} catch (Throwable failure) {
			Guard.report("freecam actions", failure);
			return false;
		}
	}
}
