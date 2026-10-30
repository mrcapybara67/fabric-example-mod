package com.capy.client.mixin;

import com.capy.client.module.ModuleManager;
import com.capy.client.module.modules.CustomFOVModule;
import com.capy.client.util.Guard;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Custom FOV, hooked on the projection's own FOV query.
 *
 * {@code getFov} is the single place where the field of view for the frame is
 * decided: vanilla reads the FOV option, multiplies it by the speed and potion
 * modifier, applies the underwater and death adjustments, and the result is
 * handed to the projection matrix. Returning a value here therefore changes the
 * actual projection, so geometry, entities and the crosshair all stay in
 * agreement — which is what a mixin on the camera or on the projection would
 * break.
 *
 * The return injection records the value that was used. The custom sky needs the
 * real field of view to place its artwork on the viewport, and this is the only
 * reading of it that is guaranteed to be the one this frame was drawn with.
 */
@Mixin(GameRenderer.class)
public class CustomFovMixin {
	@Inject(method = "getFov", at = @At("HEAD"), cancellable = true)
	private void capy$customFov(Camera camera, float partialTick, boolean useFovSetting,
			CallbackInfoReturnable<Float> cir) {
		// Once per frame, in the middle of building the projection: a failure
		// here is a crash before the first frame is even drawn, so vanilla's own
		// field of view is the fallback.
		try {
			CustomFOVModule module = ModuleManager.get().get(CustomFOVModule.class);
			// The panoramic screenshot uses a fixed 90 degree lens of its own, and
			// the player's field of view has no meaning without a player.
			if (module == null || !module.isEnabled()
					// false means "do not read the option": vanilla uses it for the
					// fixed 70 degree view, and an override there would leak into
					// renders that never asked for a field of view setting.
					|| !useFovSetting
					|| ((GameRenderer) (Object) this).isPanoramicMode()
					|| Minecraft.getInstance().player == null) {
				return;
			}
			float fov = module.frameFov();
			if (!module.overridesEffects()) {
				// Vanilla's own multiplier, so speed and potion effects still widen
				// the view while the base value comes from the module.
				fov *= ((GameRendererAccessor) (Object) this).capy$fovModifier();
			}
			CustomFOVModule.recordFov(fov);
			cir.setReturnValue(fov);
		} catch (Throwable failure) {
			Guard.report("custom fov", failure);
		}
	}

	@Inject(method = "getFov", at = @At("RETURN"))
	private void capy$recordFov(Camera camera, float partialTick, boolean useFovSetting,
			CallbackInfoReturnable<Float> cir) {
		Guard.run("fov record", () -> {
			CustomFOVModule.recordFov(cir.getReturnValueF());
		});
	}
}
