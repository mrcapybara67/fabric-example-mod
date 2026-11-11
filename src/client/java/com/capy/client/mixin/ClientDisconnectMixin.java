package com.capy.client.mixin;

import com.capy.client.module.modules.movement.MovementKickGuard;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hands the disconnect reason to the movement kick learner before the screen. */
@Mixin(ClientCommonPacketListenerImpl.class)
public class ClientDisconnectMixin {
	@Inject(method = "handleDisconnect", at = @At("HEAD"), require = 0)
	private void capy$onDisconnect(ClientboundDisconnectPacket packet, CallbackInfo ci) {
		try {
			Component reason = packet == null ? null : packet.reason();
			MovementKickGuard.onDisconnect(reason == null ? "" : reason.getString());
		} catch (Throwable failure) {
			com.capy.client.util.Guard.report("movement disconnect", failure);
		}
	}
}
