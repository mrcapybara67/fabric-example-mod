package com.capy.client.mixin;

import com.capy.client.module.modules.antiadmin.PacketSignals;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundOpenBookPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands the packets Anti Admin reads to {@link PacketSignals} before vanilla
 * applies them.
 *
 * Why a mixin and not a tick: a tick samples the state, so a player who is
 * unlisted and listed again between two samples is never seen, and a block
 * whose state changes twice only shows its last state. The packet is the event
 * itself, it arrives exactly once, and reading it at HEAD is what makes the
 * about-to-be-removed player info entry still readable.
 *
 * Every hook is one call and nothing else: the normalising, the filtering and
 * the decisions all live in {@code PacketSignals}, so this class never needs to
 * change when a detector does.
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

	@Inject(method = "handleParticleEvent", at = @At("HEAD"))
	private void capy$onParticle(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
		PacketSignals.onParticle(packet);
	}

	@Inject(method = "handleSoundEvent", at = @At("HEAD"))
	private void capy$onSound(ClientboundSoundPacket packet, CallbackInfo ci) {
		PacketSignals.onSound(packet);
	}

	@Inject(method = "handleAddEntity", at = @At("HEAD"))
	private void capy$onAddEntity(ClientboundAddEntityPacket packet, CallbackInfo ci) {
		PacketSignals.onEntitySpawn(packet);
	}

	@Inject(method = "handleBlockUpdate", at = @At("HEAD"))
	private void capy$onBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
		PacketSignals.onBlockUpdate(packet);
	}

	@Inject(method = "handleSetPlayerTeamPacket", at = @At("HEAD"))
	private void capy$onTeam(ClientboundSetPlayerTeamPacket packet, CallbackInfo ci) {
		PacketSignals.onTeam(packet);
	}

	@Inject(method = "handlePlayerChat", at = @At("HEAD"))
	private void capy$onPlayerChat(ClientboundPlayerChatPacket packet, CallbackInfo ci) {
		PacketSignals.onPlayerChat(packet);
	}

	@Inject(method = "handleSystemChat", at = @At("HEAD"))
	private void capy$onSystemChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
		PacketSignals.onSystemChat(packet);
	}

	@Inject(method = "handleOpenScreen", at = @At("HEAD"))
	private void capy$onOpenScreen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
		PacketSignals.onOpenScreen(packet);
	}

	@Inject(method = "handleOpenSignEditor", at = @At("HEAD"))
	private void capy$onOpenSign(ClientboundOpenSignEditorPacket packet, CallbackInfo ci) {
		PacketSignals.onOpenSign(packet);
	}

	@Inject(method = "handleOpenBook", at = @At("HEAD"))
	private void capy$onOpenBook(ClientboundOpenBookPacket packet, CallbackInfo ci) {
		PacketSignals.onOpenBook(packet);
	}

	@Inject(method = "handleContainerSetSlot", at = @At("HEAD"))
	private void capy$onContainerSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
		PacketSignals.onContainerSlot(packet);
	}

	@Inject(method = "handleRemoveEntities", at = @At("HEAD"))
	private void capy$onRemoveEntities(ClientboundRemoveEntitiesPacket packet, CallbackInfo ci) {
		PacketSignals.onRemoveEntities(packet);
	}
}
