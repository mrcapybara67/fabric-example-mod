package com.capy.client.mixin;

import com.capy.client.module.ModuleManager;
import com.capy.client.module.modules.FreecamModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Freecam's look control, taken at the one point where a frame's mouse movement
 * becomes rotation.
 *
 * <p>The delta is read, spent on the camera, zeroed and the vanilla turn is
 * cancelled. Zeroing as well as cancelling matters: a cancelled call leaves the
 * accumulator full, and the first frame after the module was switched off would
 * then snap the player's view by everything the mouse did while detached.</p>
 *
 * <p>Both paths leave the accumulator empty. While the camera is flying the
 * delta is spent on it; while the game is paused behind a screen or the window
 * is not focused the delta is dropped. Neither result reaches the player, so no
 * look is ever recorded or transmitted — the body keeps the rotation it was
 * standing with, which is what makes the detached view silent on the wire.</p>
 *
 * <p>Priority 900 puts this before the smooth camera, which injects into the
 * same method. Both cannot own the delta — the smoother would bank movement the
 * freecam has already spent — so the freecam takes it first and the smoother
 * sees an empty frame.</p>
 */
@Mixin(value = MouseHandler.class, priority = 900)
public class FreecamMouseMixin {
	@Inject(method = "handleAccumulatedMovement", at = @At("HEAD"), cancellable = true)
	private void capy$freecamLook(CallbackInfo ci) {
		FreecamModule freecam = ModuleManager.get().get(FreecamModule.class);
		if (freecam == null || !freecam.isActive()) {
			return;
		}
		MouseHandlerAccessor mouse = (MouseHandlerAccessor) (Object) this;
		double deltaX = mouse.capy$accumulatedDX();
		double deltaY = mouse.capy$accumulatedDY();
		Minecraft mc = Minecraft.getInstance();
		boolean steering = mc != null && mc.screen == null && mc.isWindowActive();
		if (steering) {
			freecam.applyLook(deltaX, deltaY);
		}
		// Cleared either way: while the camera is detached the body must never
		// be turned by a movement the player made in the detached view.
		mouse.capy$setAccumulatedDX(0.0);
		mouse.capy$setAccumulatedDY(0.0);
		ci.cancel();
	}
}
