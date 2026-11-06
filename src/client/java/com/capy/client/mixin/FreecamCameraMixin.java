package com.capy.client.mixin;

import com.capy.client.module.ModuleManager;
import com.capy.client.module.modules.FreecamModule;
import com.capy.client.util.Guard;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Freecam, applied where vanilla finishes placing the camera.
 *
 * <p>{@code setup} runs once per frame and ends with the position and rotation
 * of the entity being followed. Overwriting both at the end of it is the entire
 * detach: everything downstream — frustum culling, fog, the sky, the projection
 * — reads the camera's own state, so it follows automatically. Nothing is
 * spawned and nothing has to be undone when the module goes off; the next frame
 * simply gets vanilla's values again.</p>
 *
 * <p>The module is advanced from here rather than from the tick, so the camera's
 * motion is driven by the same frame that draws it. That is what makes the
 * flight immune to a starved client tick, which is how it froze on a phone.</p>
 *
 * <p>This mixin extends the class it mixes into, which is what makes the
 * protected {@code setPosition} and {@code setRotation} reachable.</p>
 */
@Mixin(Camera.class)
public abstract class FreecamCameraMixin extends Camera {
	@Inject(method = "setup", at = @At("TAIL"), require = 0)
	private void capy$freecamCamera(Level level, Entity entity, boolean detached, boolean thirdPersonReverse,
			float partialTick, CallbackInfo ci) {
		try {
			FreecamModule freecam = ModuleManager.get().get(FreecamModule.class);
			if (freecam == null || !freecam.isEnabled()) {
				return;
			}
			Minecraft mc = Minecraft.getInstance();
			if (!freecam.updateCamera(mc)) {
				return;
			}
			Vec3 position = freecam.cameraPosition();
			this.setPosition(position.x, position.y, position.z);
			// Vanilla's own order: yaw first, then pitch.
			this.setRotation(freecam.cameraYaw(), freecam.cameraPitch());
		} catch (Throwable failure) {
			// A replaced or moved camera method costs the detach, not the frame.
			Guard.report("freecam camera", failure);
		}
	}
}
