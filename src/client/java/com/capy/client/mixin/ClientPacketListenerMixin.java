package com.capy.client.mixin;

import com.capy.client.module.modules.antiadmin.PacketSignals;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands the three Anti Admin packets to {@link PacketSignals} before vanilla
 * applies them.
 *
 * Why a mixin and not a tick: a tick samples the tab list, so a player who is
 * unlisted and listed again between two scans is never seen. The packet is
 * the event itself, it arrives exactly once, and reading it before vanilla
 * applies it is what makes the about-to-be-removed entry still readable.
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
	@Inject(method = "handlePlayerInfoUpdate", at = @At("HEAD"))
	private void capy$onPlayerInfoUpdate(ClientboundPlayerInfoUpdatePacket packet, CallbackInfo ci) {
		PacketSignals.onPlayerInfoUpdate(packet);
	}

	@Inject(method = "handlePlayerInfoRemove", at = @At("HEAD"))
	private void capy$onPlayerInfoRemove(ClientboundPlayerInfoRemovePacket packet, CallbackInfo ci) {
		PacketSignals.onPlayerInfoRemove(packet);
	}

	@Inject(method = "handleCommandSuggestions", at = @At("HEAD"))
	private void capy$onCommandSuggestions(ClientboundCommandSuggestionsPacket packet, CallbackInfo ci) {
		PacketSignals.onSuggestions(packet);
	}
}
