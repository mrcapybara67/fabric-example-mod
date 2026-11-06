package com.capy.client.mixin;

import com.capy.client.module.modules.antiadmin.ChatPresence;
import com.capy.client.util.Guard;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds outgoing commands and chat to {@link ChatPresence}.
 *
 * <p>A vanished account can be invisible in both the world and the tab list,
 * but the moment the player types {@code /tpa Name} - and the server answers -
 * the name is in text the client owns. This hook remembers the command; the
 * system chat hook remembers the server's answer.</p>
 */
@Mixin(ClientPacketListener.class)
public class ChatOutboundMixin {
	@Inject(method = "sendCommand", at = @At("HEAD"))
	private void capy$rememberCommand(String command, CallbackInfo ci) {
		Guard.run("chat presence command", () -> ChatPresence.recordCommand(command));
	}

	@Inject(method = "sendChat", at = @At("HEAD"))
	private void capy$rememberChat(String message, CallbackInfo ci) {
		Guard.run("chat presence chat", () -> ChatPresence.record(message));
	}
}
