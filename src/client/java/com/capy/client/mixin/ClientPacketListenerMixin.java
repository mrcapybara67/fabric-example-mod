package com.capy.client.mixin;

import com.capy.client.antivanish.VanishPackets;
import com.capy.client.module.modules.antiadmin.PacketSignals;
import com.capy.client.module.modules.esp.FinderScanner;
import com.capy.client.module.modules.esp.MotionFeed;
import com.capy.client.module.modules.esp.SoundBypassPackets;
import com.capy.client.util.Guard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundMoveMinecartPacket;
import net.minecraft.network.protocol.game.ClientboundOpenBookPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
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
		Guard.run("anti-vanish player info update", () -> VanishPackets.onPlayerInfoUpdate(packet));
	}

	@Inject(method = "handlePlayerInfoRemove", at = @At("HEAD"))
	private void capy$onPlayerInfoRemove(ClientboundPlayerInfoRemovePacket packet, CallbackInfo ci) {
		Guard.run("anti-admin player info remove", () -> PacketSignals.onPlayerInfoRemove(packet));
		Guard.run("anti-vanish player info remove", () -> VanishPackets.onPlayerInfoRemove(packet));
	}

	@Inject(method = "handleCommandSuggestions", at = @At("HEAD"))
	private void capy$onCommandSuggestions(ClientboundCommandSuggestionsPacket packet, CallbackInfo ci) {
		Guard.run("anti-admin suggestions", () -> PacketSignals.onSuggestions(packet));
		Guard.run("anti-vanish suggestions", () -> VanishPackets.onCommandSuggestions(packet));
	}

	@Inject(method = "handleParticleEvent", at = @At("HEAD"))
	private void capy$onParticle(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
		Guard.run("anti-admin particles", () -> PacketSignals.onParticle(packet));
		Guard.run("anti-vanish particles", () -> VanishPackets.onParticle(packet));
	}

	@Inject(method = "handleSoundEvent", at = @At("HEAD"))
	private void capy$onSound(ClientboundSoundPacket packet, CallbackInfo ci) {
		Guard.run("anti-admin sound", () -> PacketSignals.onSound(packet));
		Guard.run("motion sound", () -> MotionFeed.onSound(packet.getX(), packet.getY(), packet.getZ(),
				packet.getSource()));
		Guard.run("sound bypass sound", () -> SoundBypassPackets.onSound(packet));
		Guard.run("anti-vanish sound", () -> VanishPackets.onSound(packet));
	}

	@Inject(method = "handleSoundEntityEvent", at = @At("HEAD"))
	private void capy$onSoundEntity(ClientboundSoundEntityPacket packet, CallbackInfo ci) {
		Guard.run("sound bypass entity sound", () -> SoundBypassPackets.onSoundEntity(Minecraft.getInstance(), packet));
		Guard.run("anti-vanish entity sound", () -> VanishPackets.onSoundEntity(packet));
	}

	@Inject(method = "handleAddEntity", at = @At("HEAD"))
	private void capy$onAddEntity(ClientboundAddEntityPacket packet, CallbackInfo ci) {
		Guard.run("anti-admin entity spawn", () -> PacketSignals.onEntitySpawn(packet));
	}

	@Inject(method = "handleAddEntity", at = @At("TAIL"))
	private void capy$onAddEntitySoundBypass(ClientboundAddEntityPacket packet, CallbackInfo ci) {
		Guard.run("sound bypass entity add", () -> SoundBypassPackets.onAddEntity(Minecraft.getInstance(), packet));
	}

	@Inject(method = "handleMoveEntity", at = @At("TAIL"))
	private void capy$onMoveEntity(ClientboundMoveEntityPacket packet, CallbackInfo ci) {
		Guard.run("motion entity move", () -> {
			Minecraft mc = Minecraft.getInstance();
			if (packet.hasPosition() && mc != null && mc.level != null) {
				var entity = packet.getEntity(mc.level);
				MotionFeed.onEntityMoved(entity);
				SoundBypassPackets.onMoveEntity(mc, packet, entity);
			}
		});
	}

	@Inject(method = "handleTeleportEntity", at = @At("TAIL"))
	private void capy$onTeleportEntity(ClientboundTeleportEntityPacket packet, CallbackInfo ci) {
		Guard.run("motion entity teleport", () -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.level != null) {
				MotionFeed.onEntityMoved(mc.level.getEntity(packet.id()));
			}
		});
	}

	@Inject(method = "handleEntityPositionSync", at = @At("TAIL"))
	private void capy$onEntityPositionSync(ClientboundEntityPositionSyncPacket packet, CallbackInfo ci) {
		Guard.run("motion entity sync", () -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.level != null) {
				MotionFeed.onEntityMoved(mc.level.getEntity(packet.id()));
			}
		});
	}

	@Inject(method = "handleMinecartAlongTrack", at = @At("TAIL"))
	private void capy$onMinecartAlongTrack(ClientboundMoveMinecartPacket packet, CallbackInfo ci) {
		Guard.run("motion minecart", () -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.level != null) {
				MotionFeed.onEntityMoved(packet.getEntity(mc.level));
			}
		});
	}

	@Inject(method = "handleBlockUpdate", at = @At("HEAD"))
	private void capy$onBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
		Guard.run("anti-admin block update", () -> PacketSignals.onBlockUpdate(packet));
		Guard.run("anti-vanish block update", () -> VanishPackets.onBlockUpdate(packet.getPos(), packet.getBlockState()));
		Guard.run("finder block update", () -> FinderScanner.get().onBlockUpdate(packet.getPos()));
		Guard.run("motion block update", () -> MotionFeed.onBlockUpdate(packet.getPos(), packet.getBlockState()));
	}

	@Inject(method = "handleBlockEvent", at = @At("HEAD"))
	private void capy$onBlockEvent(ClientboundBlockEventPacket packet, CallbackInfo ci) {
		Guard.run("motion block event", () -> MotionFeed.onBlockEvent(packet.getBlock(), packet.getPos()));
		Guard.run("sound bypass block event", () -> SoundBypassPackets.onBlockEvent(packet));
	}

	@Inject(method = "handleLevelEvent", at = @At("HEAD"))
	private void capy$onLevelEvent(ClientboundLevelEventPacket packet, CallbackInfo ci) {
		Guard.run("sound bypass level event", () -> SoundBypassPackets.onLevelEvent(packet));
	}

	@Inject(method = "handleBlockDestruction", at = @At("HEAD"))
	private void capy$onBlockDestruction(ClientboundBlockDestructionPacket packet, CallbackInfo ci) {
		Guard.run("sound bypass destruction", () -> SoundBypassPackets.onBlockDestruction(packet));
	}

	@Inject(method = "handleChunkBlocksUpdate", at = @At("HEAD"))
	private void capy$onChunkBlocksUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
		Guard.run("finder section update", () -> FinderScanner.get().onSectionBlocksUpdate(packet));
		Guard.run("motion section update", () -> MotionFeed.onSectionUpdate(packet));
	}

	@Inject(method = "handleLevelChunkWithLight", at = @At("HEAD"))
	private void capy$onLevelChunkWithLight(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
		Guard.run("finder chunk data", () -> FinderScanner.get().onChunkData(packet.getX(), packet.getZ()));
	}

	@Inject(method = "handleLightUpdatePacket", at = @At("HEAD"))
	private void capy$onLightUpdate(ClientboundLightUpdatePacket packet, CallbackInfo ci) {
		Guard.run("finder light update", () -> FinderScanner.get().onLightUpdate(packet.getX(), packet.getZ()));
	}

	@Inject(method = "handleSetPlayerTeamPacket", at = @At("HEAD"))
	private void capy$onTeam(ClientboundSetPlayerTeamPacket packet, CallbackInfo ci) {
		Guard.run("anti-admin team", () -> PacketSignals.onTeam(packet));
	}

	@Inject(method = "handlePlayerChat", at = @At("HEAD"))
	private void capy$onPlayerChat(ClientboundPlayerChatPacket packet, CallbackInfo ci) {
		Guard.run("anti-admin chat", () -> PacketSignals.onPlayerChat(packet));
	}

	@Inject(method = "handleSystemChat", at = @At("HEAD"))
	private void capy$onSystemChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
		Guard.run("anti-admin system chat", () -> PacketSignals.onSystemChat(packet));
		Guard.run("anti-vanish system chat", () -> VanishPackets.onSystemChat(packet));
		Guard.run("movement kick chat", () -> {
			if (packet != null) {
				com.capy.client.module.modules.movement.MovementKickGuard.onSystemChat(packet.content().getString());
			}
		});
	}

	@Inject(method = "handleSetEntityData", at = @At("TAIL"))
	private void capy$onSetEntityData(ClientboundSetEntityDataPacket packet, CallbackInfo ci) {
		Guard.run("anti-vanish entity data", VanishPackets::onEntityData);
	}

	@Inject(method = "handleOpenScreen", at = @At("HEAD"))
	private void capy$onOpenScreen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
		Guard.run("anti-admin open screen", () -> PacketSignals.onOpenScreen(packet));
		Guard.run("anti-vanish container open", () -> VanishPackets.onContainer(true));
	}

	@Inject(method = "handleOpenSignEditor", at = @At("HEAD"))
	private void capy$onOpenSign(ClientboundOpenSignEditorPacket packet, CallbackInfo ci) {
		Guard.run("anti-admin open sign", () -> PacketSignals.onOpenSign(packet));
	}

	@Inject(method = "handleOpenBook", at = @At("HEAD"))
	private void capy$onOpenBook(ClientboundOpenBookPacket packet, CallbackInfo ci) {
		Guard.run("anti-admin open book", () -> PacketSignals.onOpenBook(packet));
	}

	@Inject(method = "handleContainerClose", at = @At("HEAD"))
	private void capy$onContainerClose(ClientboundContainerClosePacket packet, CallbackInfo ci) {
		Guard.run("anti-vanish container close", () -> VanishPackets.onContainer(false));
	}

	@Inject(method = "handleExplosion", at = @At("HEAD"))
	private void capy$onExplosion(ClientboundExplodePacket packet, CallbackInfo ci) {
		Guard.run("anti-vanish explosion", () -> VanishPackets.onExplosion(packet));
	}

	@Inject(method = "handleContainerSetSlot", at = @At("HEAD"))
	private void capy$onContainerSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
		Guard.run("anti-admin container slot", () -> PacketSignals.onContainerSlot(packet));
	}

	@Inject(method = "handleRemoveEntities", at = @At("HEAD"))
	private void capy$onRemoveEntities(ClientboundRemoveEntitiesPacket packet, CallbackInfo ci) {
		Guard.run("anti-admin remove entities", () -> PacketSignals.onRemoveEntities(packet));
		Guard.run("sound bypass remove entities", () -> SoundBypassPackets.onRemoveEntities(packet));
	}
}
