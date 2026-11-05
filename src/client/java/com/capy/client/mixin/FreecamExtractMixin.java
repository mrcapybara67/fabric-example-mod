package com.capy.client.mixin;

import com.capy.client.module.ModuleManager;
import com.capy.client.module.modules.FreecamModule;
import com.capy.client.util.Guard;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Freecam, applied a second time where the frame's camera state is extracted.
 *
 * <h2>Why a second hook exists</h2>
 * 1.21.11 no longer draws straight from the {@code Camera} object: the game
 * copies the camera's position and orientation into a {@link CameraRenderState}
 * once per frame, and the whole frame — terrain, entities, fog, the sky — is
 * drawn from that copy. Overwriting the camera itself is still necessary (the
 * culling and the near plane read it), but on the frames where the copy is
 * taken before the camera has been moved, the frame is drawn from the body and
 * the module appears to do nothing at all, which is exactly the "camera stuck in
 * the body" report.
 *
 * <p>Writing the extracted state as well closes that window. Both writes use the
 * module's own values, so on a normal frame this is the same position and
 * rotation written twice; on an early-extraction frame it is the difference
 * between a detached camera and none.
 *
 * <h2>Only while primed</h2>
 * {@code isActive()} is false until the module has placed the camera at the
 * player's eye, so the first frame of a toggle still draws vanilla rather than a
 * camera at the origin, and a disabled module never overrides anything.
 */
@Mixin(GameRenderer.class)
public class FreecamExtractMixin {
	@Shadow
	private LevelRenderState levelRenderState;

	@Inject(method = "extractCamera", at = @At("TAIL"), require = 0)
	private void capy$freecamExtractCamera(float partialTick, CallbackInfo ci) {
		try {
			FreecamModule freecam = ModuleManager.get().get(FreecamModule.class);
			if (freecam == null || !freecam.isActive() || levelRenderState == null) {
				return;
			}
			CameraRenderState state = levelRenderState.cameraRenderState;
			if (state == null) {
				return;
			}
			Vec3 position = freecam.cameraPosition();
			state.pos = position;
			state.blockPos = BlockPos.containing(position);
			// Vanilla builds the camera's rotation as rotationYXZ(pi - yaw,
			// -pitch, 0); the extracted state expects the same quaternion, so a
			// hand-built one has to use the same order and signs or the world is
			// drawn facing the wrong way.
			float yawRadians = freecam.cameraYaw() * 0.017453292F;
			float pitchRadians = freecam.cameraPitch() * 0.017453292F;
			state.orientation = new Quaternionf()
					.rotationYXZ((float) Math.PI - yawRadians, -pitchRadians, 0.0f);
		} catch (Throwable failure) {
			// The setup hook still runs; a failure here must not cost the frame.
			Guard.report("freecam extract", failure);
		}
	}
}
