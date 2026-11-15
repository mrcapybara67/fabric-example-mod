package com.capy.client.mixin;

import com.capy.client.Stealth;
import com.capy.client.stealth.ChannelFilter;
import java.util.List;
import net.fabricmc.fabric.impl.networking.RegistrationPayload;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Fabric builds a RegistrationPayload for the configuration and play channel
 * announcements. Filter that outgoing payload rather than registering or
 * sending a packet of our own.
 */
@Mixin(Connection.class)
public class ConnectionChannelMixin {
	@ModifyVariable(method = "send(Lnet/minecraft/network/protocol/Packet;)V",
			at = @At("HEAD"), argsOnly = true)
	private Packet<?> capy$filterChannelAnnouncement(Packet<?> packet) {
		if (!Stealth.enabled() || !(packet instanceof ServerboundCustomPayloadPacket custom)
				|| !(custom.payload() instanceof RegistrationPayload registration)) {
			return packet;
		}
		List<Identifier> channels = registration.channels();
		List<Identifier> kept = ChannelFilter.filter(channels);
		if (kept.size() == channels.size()) {
			return packet;
		}
		return new ServerboundCustomPayloadPacket(new RegistrationPayload(registration.id(), kept));
	}
}
