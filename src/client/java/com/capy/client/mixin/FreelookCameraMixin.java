package com.capy.client.mixin;

import com.capy.client.module.ModuleManager;
import com.capy.client.module.modules.FreecamModule;
import com.capy.client.module.modules.FreelookModule;
import com.capy.client.util.Guard;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Overwrites vanilla's camera with Freelook's orbit at the end of setup. */
@Mixin(Camera.class)
public abstract class FreelookCameraMixin {
	@Shadow
	protected abstract void setPosition(double x, double y, double z);

	@Shadow
	protected abstract void setRotation(float yaw, float pitch);

	@Inject(method = "setup", at = @At("TAIL"), require = 0)
	private void capy$freelookCamera(Level level, Entity entity, boolean detached,
			boolean thirdPersonReverse, float partialTick, CallbackInfo ci) {
		try {
			FreecamModule freecam = ModuleManager.get().get(FreecamModule.class);
			if (freecam != null && freecam.isActive()) {
				// Freecam is the stronger detach; it owns the camera when both
				// are on so the two never fight over one position.
				return;
			}
			FreelookModule freelook = ModuleManager.get().get(FreelookModule.class);
			if (freelook == null || !freelook.updateCamera(Minecraft.getInstance(), partialTick)) {
				return;
			}
			Vec3 position = freelook.cameraPosition();
			this.setPosition(position.x, position.y, position.z);
			this.setRotation(freelook.cameraYaw(), freelook.cameraPitch());
		} catch (Throwable failure) {
			Guard.report("freelook camera", failure);
		}
	}
}
