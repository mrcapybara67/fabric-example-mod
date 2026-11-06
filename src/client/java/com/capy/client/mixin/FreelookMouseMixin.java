package com.capy.client.mixin;

import com.capy.client.module.ModuleManager;
import com.capy.client.module.modules.FreecamModule;
import com.capy.client.module.modules.FreelookModule;
import com.capy.client.util.Guard;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Freelook's look control. Priority 950 runs before Freecam's 900: if a player
 * somehow has both modules on, the orbit takes the delta and Freecam sees an
 * empty frame, so the camera cannot be turned twice.
 */
@Mixin(value = MouseHandler.class, priority = 950)
public class FreelookMouseMixin {
	@Inject(method = "handleAccumulatedMovement", at = @At("HEAD"), cancellable = true, require = 0)
	private void capy$freelookLook(CallbackInfo ci) {
		try {
			FreecamModule freecam = ModuleManager.get().get(FreecamModule.class);
			if (freecam != null && freecam.isActive()) {
				return;
			}
			FreelookModule freelook = ModuleManager.get().get(FreelookModule.class);
			if (freelook == null || !freelook.isActive()) {
				return;
			}
			MouseHandlerAccessor mouse = (MouseHandlerAccessor) (Object) this;
			freelook.applyLook(mouse.capy$accumulatedDX(), mouse.capy$accumulatedDY());
			mouse.capy$setAccumulatedDX(0.0);
			mouse.capy$setAccumulatedDY(0.0);
			ci.cancel();
		} catch (Throwable failure) {
			Guard.report("freelook look", failure);
		}
	}
}
