package com.capy.client.module.modules.esp;

import com.capy.client.finder.core.SoundBypassFeed;
import com.capy.client.finder.core.SoundKinds;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AbstractChestBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ConcretePowderBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Decodes the packets Sound Bypass reads. Every method returns immediately
 * unless the module has acquired the feed, and none of them writes anything
 * back to the connection.
 */
public final class SoundBypassPackets {
	private static final double BOOSTED_BREAK = 2.0;

	private SoundBypassPackets() {
	}

	private static SoundBypassFeed feed() {
		return SoundBypassFeed.get();
	}

	private static long now() {
		return System.currentTimeMillis();
	}

	public static void onSound(ClientboundSoundPacket packet) {
		SoundBypassFeed feed = feed();
		if (!feed.active() || packet == null) {
			return;
		}
		String id = packet.getSound() == null || packet.getSound().value() == null
				? null : packet.getSound().value().location().toString();
		feed.onSound(id, packet.getX(), packet.getY(), packet.getZ(), now());
	}

	public static void onSoundEntity(Minecraft mc, ClientboundSoundEntityPacket packet) {
		SoundBypassFeed feed = feed();
		if (!feed.active() || mc == null || mc.level == null || packet == null || packet.getSound() == null
				|| packet.getSound().value() == null) {
			return;
		}
		Entity entity = mc.level.getEntity(packet.getId());
		if (entity == null) {
			return;
		}
		feed.onSound(packet.getSound().value().location().toString(),
				entity.getX(), entity.getY(), entity.getZ(), now());
	}

	public static void onLevelEvent(ClientboundLevelEventPacket packet) {
		SoundBypassFeed feed = feed();
		if (!feed.active() || packet == null) {
			return;
		}
		BlockPos pos = packet.getPos();
		double x = pos.getX() + 0.5;
		double y = pos.getY() + 0.5;
		double z = pos.getZ() + 0.5;
		switch (packet.getType()) {
			case LevelEvent.PARTICLES_DESTROY_BLOCK -> {
				BlockState state = Block.stateById(packet.getData());
				if (state == null) {
					return;
				}
				feed.onLevelEvent(SoundKinds.Kind.MINING, boostedBreak(state) ? BOOSTED_BREAK : 1.0, x, y, z, now());
			}
			case LevelEvent.PARTICLES_MOBBLOCK_SPAWN -> feed.onStructure(x, y, z, now());
			case LevelEvent.SOUND_DISPENSER_DISPENSE, LevelEvent.SOUND_DISPENSER_FAIL,
					LevelEvent.SOUND_DISPENSER_PROJECTILE_LAUNCH ->
					feed.onLevelEvent(SoundKinds.Kind.REDSTONE, 2.0, x, y, z, now());
			default -> {
				// Every other level event is ambience or a visual effect.
			}
		}
	}

	public static void onBlockEvent(ClientboundBlockEventPacket packet) {
		SoundBypassFeed feed = feed();
		if (!feed.active() || packet == null || packet.getBlock() == null) {
			return;
		}
		Block block = packet.getBlock();
		BlockPos pos = packet.getPos();
		double x = pos.getX() + 0.5;
		double y = pos.getY() + 0.5;
		double z = pos.getZ() + 0.5;
		if (block instanceof AbstractChestBlock<?> || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock) {
			feed.onBlockEvent(SoundKinds.Kind.STORAGE, 3.0, x, y, z, now());
		} else if (block instanceof PistonBaseBlock || block == Blocks.PISTON_HEAD || block == Blocks.MOVING_PISTON) {
			feed.onBlockEvent(SoundKinds.Kind.REDSTONE, 2.0, x, y, z, now());
		}
		// Note blocks and bells fall through on purpose: they are music, not activity.
	}

	public static void onBlockDestruction(ClientboundBlockDestructionPacket packet) {
		SoundBypassFeed feed = feed();
		if (!feed.active() || packet == null) {
			return;
		}
		BlockPos pos = packet.getPos();
		feed.onDestruction(pos.getX(), pos.getY(), pos.getZ(), packet.getProgress(), now());
	}

	public static void onAddEntity(Minecraft mc, ClientboundAddEntityPacket packet) {
		SoundBypassFeed feed = feed();
		if (!feed.active() || mc == null || mc.level == null || packet == null) {
			return;
		}
		EntityType<?> type = packet.getType();
		double x = packet.getX();
		double y = packet.getY();
		double z = packet.getZ();
		if (type == EntityType.TNT) {
			feed.onEntityAdd(packet.getId(), SoundBypassFeed.Tracked.TNT, x, y, z, false, now());
		} else if (type == EntityType.FALLING_BLOCK) {
			feed.onEntityAdd(packet.getId(), SoundBypassFeed.Tracked.FALLING_BLOCK, x, y, z, false, now());
		} else if (type == EntityType.ITEM) {
			Entity entity = mc.level.getEntity(packet.getId());
			boolean known = entity instanceof ItemEntity item && !item.getItem().isEmpty();
			// If the stack has not arrived yet, track it as natural (ignore it)
			// until a move carries the real stack; that keeps sand-farm drops
			// from counting during the gap.
			boolean natural = !known || naturalItem(((ItemEntity) entity).getItem());
			feed.onEntityAdd(packet.getId(), SoundBypassFeed.Tracked.ITEM, x, y, z, natural, now());
		} else if (type == EntityType.EXPERIENCE_ORB) {
			feed.onEntityAdd(packet.getId(), SoundBypassFeed.Tracked.XP_ORB, x, y, z, false, now());
		}
	}

	public static void onMoveEntity(Minecraft mc, ClientboundMoveEntityPacket packet, Entity entity) {
		SoundBypassFeed feed = feed();
		if (!feed.active() || mc == null || mc.level == null || packet == null || entity == null
				|| !packet.hasPosition()) {
			return;
		}
		boolean natural = false;
		if (entity instanceof ItemEntity item) {
			if (item.getItem().isEmpty()) {
				return; // Keep ignoring it until the stack is known.
			}
			natural = naturalItem(item.getItem());
		}
		feed.onEntityMove(entity.getId(), entity.getX(), entity.getY(), entity.getZ(), natural, now());
	}

	public static void onRemoveEntities(ClientboundRemoveEntitiesPacket packet) {
		SoundBypassFeed feed = feed();
		if (!feed.active() || packet == null) {
			return;
		}
		for (int id : packet.getEntityIds()) {
			feed.onEntityRemove(id, now());
		}
	}

	private static boolean boostedBreak(BlockState state) {
		Block block = state.getBlock();
		return ore(state) || block == Blocks.SPAWNER || block == Blocks.TRIAL_SPAWNER || block == Blocks.VAULT
				|| block instanceof AbstractChestBlock<?> || block instanceof BarrelBlock
				|| block instanceof ShulkerBoxBlock;
	}

	private static boolean ore(BlockState state) {
		return state.is(BlockTags.COAL_ORES) || state.is(BlockTags.COPPER_ORES)
				|| state.is(BlockTags.DIAMOND_ORES) || state.is(BlockTags.EMERALD_ORES)
				|| state.is(BlockTags.GOLD_ORES) || state.is(BlockTags.IRON_ORES)
				|| state.is(BlockTags.LAPIS_ORES) || state.is(BlockTags.REDSTONE_ORES)
				|| state.getBlock() == Blocks.ANCIENT_DEBRIS || state.getBlock() == Blocks.NETHER_QUARTZ_ORE;
	}

	private static boolean naturalItem(ItemStack stack) {
		if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
			return false;
		}
		Block block = blockItem.getBlock();
		return block == Blocks.SAND || block == Blocks.GRAVEL || block == Blocks.SUSPICIOUS_SAND
				|| block == Blocks.SUSPICIOUS_GRAVEL || block instanceof ConcretePowderBlock;
	}
}
