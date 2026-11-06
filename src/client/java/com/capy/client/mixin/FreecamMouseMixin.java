package com.capy.client.mixin;

import com.capy.client.module.ModuleManager;
import com.capy.client.module.modules.FreecamModule;
import com.capy.client.util.Guard;
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
 * <p>There is deliberately no window-focus or screen check here. An earlier
 * version only steered while {@code Minecraft.isWindowActive()} was true, and
 * Android launchers report the window as inactive even while the player is
 * playing — so every delta was dropped and the camera never turned. A delta that
 * reached this handler is a delta the player produced, so it is spent.</p>
 *
 * <p>Priority 900 puts this before the smooth camera, which injects into the
 * same method. Both cannot own the delta — the smoother would bank movement the
 * freecam has already spent — so the freecam takes it first and the smoother
 * sees an empty frame.</p>
 */
@Mixin(value = MouseHandler.class, priority = 900)
public class FreecamMouseMixin {
	@Inject(method = "handleAccumulatedMovement", at = @At("HEAD"), cancellable = true, require = 0)
	private void capy$freecamLook(CallbackInfo ci) {
		try {
			FreecamModule freecam = ModuleManager.get().get(FreecamModule.class);
			if (freecam == null || !freecam.isActive()) {
				return;
			}
			MouseHandlerAccessor mouse = (MouseHandlerAccessor) (Object) this;
			freecam.applyLook(mouse.capy$accumulatedDX(), mouse.capy$accumulatedDY());
			// Cleared as well as cancelled: while the camera is detached the body
			// must never be turned by a movement the player made in that view.
			mouse.capy$setAccumulatedDX(0.0);
			mouse.capy$setAccumulatedDY(0.0);
			ci.cancel();
		} catch (Throwable failure) {
			// The vanilla turn still runs; a lost frame of look is not a crash.
			Guard.report("freecam look", failure);
		}
	}
}
