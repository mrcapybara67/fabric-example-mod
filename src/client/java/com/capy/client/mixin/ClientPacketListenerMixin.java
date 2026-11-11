package com.capy.client.mixin;

import com.capy.client.module.modules.antiadmin.PacketSignals;
import com.capy.client.util.Guard;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
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
 * <p>Every hook is one guarded call and nothing else. The guard matters more
 * here than anywhere else in the client: {@code ClientPacketListener} is the
 * class that is loaded when the player first connects, so an exception in one
 * of these fifteen handlers is exactly "the game crashes the moment I join a
 * server", and a single unparseable suggestion reply would take the whole
 * client down with it. A swallowed, logged failure costs one detection; a
 * thrown one costs the session.</p>
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
	@Inject(method = "handlePlayerInfoUpdate", at = @At("HEAD"))
	private void capy$onPlayerInfoUpdate(ClientboundPlayerInfoUpdatePacket packet, CallbackInfo ci) {
		Guard.run("anti-admin player info update", () -> PacketSignals.onPlayerInfoUpdate(packet));
	}

	@Inject(method = "handlePlayerInfoRemove", at = @At("HEAD"))
	private void capy$onPlayerInfoRemove(ClientboundPlayerInfoRemovePacket packet, CallbackInfo ci) {
		Guard.run("anti-admin player info remove", () -> PacketSignals.onPlayerInfoRemove(packet));
	}

	@Inject(method = "handleCommandSuggestions", at = @At("HEAD"))
	private void capy$onCommandSuggestions(ClientboundCommandSuggestionsPacket packet, CallbackInfo ci) {
		Guard.run("anti-admin suggestions", () -> PacketSignals.onSuggestions(packet));
	}

	@Inject(method = "handleParticleEvent", at = @At("HEAD"))
	private void capy$onParticle(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
		Guard.run("anti-admin particles", () -> PacketSignals.onParticle(packet));
	}

	@Inject(method = "handleSoundEvent", at = @At("HEAD"))
	private void capy$onSound(ClientboundSoundPacket packet, CallbackInfo ci) {
		Guard.run("anti-admin sound", () -> PacketSignals.onSound(packet));
	}

	@Inject(method = "handleAddEntity", at = @At("HEAD"))
	private void capy$onAddEntity(ClientboundAddEntityPacket packet, CallbackInfo ci) {
		Guard.run("anti-admin entity spawn", () -> PacketSignals.onEntitySpawn(packet));
	}

	@Inject(method = "handleBlockUpdate", at = @At("HEAD"))
	private void capy$onBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
		Guard.run("anti-admin block update", () -> PacketSignals.onBlockUpdate(packet));
	}

	@Inject(method = "handleSetPlayerTeamPacket", at = @At("HEAD"))
	private void capy$onTeam(ClientboundSetPlayerTeamPacket packet, CallbackInfo ci) {
		Guard.run("anti-admin team", () -> PacketSignals.onTeam(packet));
	}

	@Inject(method = "handlePlayerChat", at = @At("HEAD"))
	private void capy$onPlayerChat(ClientboundPlayerChatPacket packet, CallbackInfo ci) {
		Guard.run("anti-admin chat", () -> PacketSignals.onPlayerChat(packet));
	}

	@Inject(method = "handleLevelChunkWithLight", at = @At("HEAD"))
	private void capy$onLevelChunk(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
		Guard.run("light chunk", () -> {
			if (packet != null) {
				com.capy.client.module.modules.esp.ChunkFinderModule.onChunkPacket(
						packet.getX(), packet.getZ(), packet.getLightData());
			}
		});
	}

	@Inject(method = "handleLightUpdatePacket", at = @At("HEAD"))
	private void capy$onLightPacket(ClientboundLightUpdatePacket packet, CallbackInfo ci) {
		Guard.run("light update", () -> {
			if (packet != null) {
				com.capy.client.module.modules.esp.ChunkFinderModule.onLightPacket(
						packet.getX(), packet.getZ(), packet.getLightData());
			}
		});
	}

	@Inject(method = "handleSystemChat", at = @At("HEAD"))
	private void capy$onSystemChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
		Guard.run("anti-admin system chat", () -> PacketSignals.onSystemChat(packet));
		Guard.run("movement kick chat", () -> {
			if (packet != null) {
				com.capy.client.module.modules.movement.MovementKickGuard.onSystemChat(packet.content().getString());
			}
		});
	}

	@Inject(method = "handleOpenScreen", at = @At("HEAD"))
	private void capy$onOpenScreen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
		Guard.run("anti-admin open screen", () -> PacketSignals.onOpenScreen(packet));
	}

	@Inject(method = "handleOpenSignEditor", at = @At("HEAD"))
	private void capy$onOpenSign(ClientboundOpenSignEditorPacket packet, CallbackInfo ci) {
		Guard.run("anti-admin open sign", () -> PacketSignals.onOpenSign(packet));
	}

	@Inject(method = "handleOpenBook", at = @At("HEAD"))
	private void capy$onOpenBook(ClientboundOpenBookPacket packet, CallbackInfo ci) {
		Guard.run("anti-admin open book", () -> PacketSignals.onOpenBook(packet));
	}

	@Inject(method = "handleContainerSetSlot", at = @At("HEAD"))
	private void capy$onContainerSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
		Guard.run("anti-admin container slot", () -> PacketSignals.onContainerSlot(packet));
	}

	@Inject(method = "handleRemoveEntities", at = @At("HEAD"))
	private void capy$onRemoveEntities(ClientboundRemoveEntitiesPacket packet, CallbackInfo ci) {
		Guard.run("anti-admin remove entities", () -> PacketSignals.onRemoveEntities(packet));
	}
}
