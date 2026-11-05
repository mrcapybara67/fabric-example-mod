package com.capy.client.mixin;

import com.capy.client.module.modules.BoatFlyModule;
import com.capy.client.util.Guard;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reports a flying boat as grounded, on the packet itself.
 *
 * <p>Vanilla's "vehicle floating too long" check reads the ground flag of the
 * movement packet, and the packet is built from the boat's flag at whatever
 * point in the tick it happens to be sent - which can be after the boat's own
 * tick has already set it back to false. Setting the flag on the boat alone is
 * therefore a race. Rebuilding the packet here removes the race: the position,
 * yaw and pitch are the vanilla ones, only the ground bit differs, and only
 * for the vehicle a boat flight module is currently driving.</p>
 */
@Mixin(ServerboundMoveVehiclePacket.class)
public abstract class BoatPacketMixin {
	@Inject(method = "fromEntity", at = @At("RETURN"), cancellable = true)
	private static void capy$groundVehicle(Entity entity,
			CallbackInfoReturnable<ServerboundMoveVehiclePacket> cir) {
		try {
			if (BoatFlyModule.activeFor(entity) == null) {
				return;
			}
			ServerboundMoveVehiclePacket packet = cir.getReturnValue();
			if (packet == null || packet.onGround()) {
				return;
			}
			cir.setReturnValue(new ServerboundMoveVehiclePacket(
					packet.position(), packet.yRot(), packet.xRot(), true));
		} catch (Throwable failure) {
			Guard.report("boat fly", failure);
		}
	}
}
