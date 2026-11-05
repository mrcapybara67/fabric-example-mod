package com.capy.client.mixin;

import com.capy.client.module.modules.BoatFlyModule;
import com.capy.client.util.Guard;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops vanilla's boat from paddling itself while a boat flight module is
 * flying it.
 *
 * <p>{@code setInput} is called from the rider's tick with the four movement
 * keys; the boat's own tick then turns those flags into a small velocity and a
 * rotation. That velocity is written with {@code setDeltaMovement}, so it
 * would replace the speed the module asked for, and the rotation would fight
 * the camera steering. Clearing the flags here leaves the rest of the boat
 * tick - gravity, buoyancy, collision, passenger placement and its packets -
 * running exactly as vanilla does, which is what keeps the ride intact.</p>
 */
@Mixin(AbstractBoat.class)
public abstract class BoatInputMixin {
	@Shadow
	private boolean inputLeft;
	@Shadow
	private boolean inputRight;
	@Shadow
	private boolean inputUp;
	@Shadow
	private boolean inputDown;

	@Inject(method = "setInput", at = @At("HEAD"), cancellable = true)
	private void capy$blockPaddle(boolean left, boolean right, boolean up, boolean down,
			CallbackInfo ci) {
		try {
			if (BoatFlyModule.activeFor((AbstractBoat) (Object) this) == null) {
				return;
			}
			// Clear before cancelling: the flags persist between ticks, so
			// merely returning would leave the last paddled direction alive.
			inputLeft = false;
			inputRight = false;
			inputUp = false;
			inputDown = false;
			ci.cancel();
		} catch (Throwable failure) {
			Guard.report("boat fly", failure);
		}
	}
}
