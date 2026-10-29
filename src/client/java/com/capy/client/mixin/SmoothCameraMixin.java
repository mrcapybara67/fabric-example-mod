package com.capy.client.mixin;

import com.capy.client.module.ModuleManager;
import com.capy.client.module.modules.SmoothCameraModule;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Smooth Camera, hooked at the one point where a frame's mouse movement becomes
 * camera rotation.
 *
 * {@code handleAccumulatedMovement} runs once per frame: it samples the summed
 * mouse delta, passes it to {@code turnPlayer}, and zeroes the accumulator. The
 * module rewrites that accumulator at the head of the call, so vanilla still
 * does all of the work it always does — sensitivity, the cinematic camera, the
 * third person behind-the-shoulder offset, the turn ordering for weapons — with
 * a value that has been smoothed.
 *
 * The alternative, a mixin on {@code GameRenderer} rotating the camera itself,
 * would move the view without moving the player, and the crosshair would stop
 * pointing where the player is actually aiming.
 */
@Mixin(MouseHandler.class)
public class SmoothCameraMixin {
	@Inject(method = "handleAccumulatedMovement", at = @At("HEAD"))
	private void capy$smoothCamera(CallbackInfo ci) {
		SmoothCameraModule module = ModuleManager.get().get(SmoothCameraModule.class);
		if (module == null || !module.isEnabled()) {
			return;
		}
		module.applyTo((MouseHandler) (Object) this);
	}
}
