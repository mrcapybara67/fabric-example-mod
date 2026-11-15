package com.capy.client.module.modules.esp;

import com.capy.client.finder.core.Chunks;
import com.capy.client.finder.core.MotionScores;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.block.AbstractChestBlock;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Shared live activity bus for Motion Scanner and Activity Inspector.
 *
 * <p>Everything here comes from packets the client already receives. The
 * score maps only run while at least one module asks for them; toggling both
 * modules off releases the bus and drops its state. The low-Y map is fed in
 * parallel so the inspector can mark chunks where the activity was below
 * Y 0 without keeping a second copy of every event.</p>
 */
public final class MotionFeed {
	private static final MotionScores SCORES = new MotionScores();
	private static final MotionScores LOW_SCORES = new MotionScores();
	private static final AtomicInteger USERS = new AtomicInteger();

	/** Last open state of a door/trapdoor/gate, by block position. */
	private static final Map<Long, Boolean> DOOR_OPEN = new HashMap<>();
	/** Last lit state of a furnace, by block position. */
	private static final Map<Long, Boolean> FURNACE_LIT = new HashMap<>();

	private MotionFeed() {
	}

	public static void acquire() {
		USERS.incrementAndGet();
	}

	public static void release() {
		if (USERS.decrementAndGet() <= 0) {
			USERS.set(0);
			reset();
		}
	}

	public static boolean active() {
		return USERS.get() > 0;
	}

	public static MotionScores scores() {
		return SCORES;
	}

	public static MotionScores lowScores() {
		return LOW_SCORES;
	}

	/** A block update. Doors and gates score 3 when they open or close; furnaces score 2 when lit changes. */
	public static synchronized void onBlockUpdate(BlockPos pos, BlockState state) {
		if (!active() || pos == null || state == null) {
			return;
		}
		long stateKey = Chunks.block(pos.getX(), pos.getY(), pos.getZ());
		double weight = 1.0;
		Block block = state.getBlock();

		Boolean open = doorOpen(block, state);
		if (open != null) {
			Boolean before = DOOR_OPEN.put(stateKey, open);
			if (before != null && !before.equals(open)) {
				weight = 3.0;
			}
		} else {
			DOOR_OPEN.remove(stateKey);
		}

		if (block instanceof AbstractFurnaceBlock && state.hasProperty(AbstractFurnaceBlock.LIT)) {
			boolean lit = state.getValue(AbstractFurnaceBlock.LIT);
			Boolean before = FURNACE_LIT.put(stateKey, lit);
			if (before != null && before != lit) {
				weight = 2.0;
			}
		} else {
			FURNACE_LIT.remove(stateKey);
		}

		add(chunk(pos), weight, pos.getY());
	}

	/** A block event, such as a chest opening. Containers and doors weigh 3. */
	public static synchronized void onBlockEvent(Block block, BlockPos pos) {
		if (!active() || pos == null) {
			return;
		}
		add(chunk(pos), containerOrDoor(block) ? 3.0 : 1.0, pos.getY());
	}

	/** A block sound; only the blocks source counts. */
	public static void onSound(double x, double y, double z, SoundSource source) {
		if (source != SoundSource.BLOCKS) {
			return;
		}
		addAt(x, y, z, 0.5);
	}

	/** Item entities and minecarts moving under their own power. */
	public static void onEntityMoved(Entity entity) {
		if (entity == null || entity.isRemoved()) {
			return;
		}
		if (entity instanceof ItemEntity || entity instanceof AbstractMinecart) {
			addAt(entity.getX(), entity.getY(), entity.getZ(), 1.0);
		}
	}

	/** A section update contains many block updates; each one feeds the same rules. */
	public static void onSectionUpdate(ClientboundSectionBlocksUpdatePacket packet) {
		if (packet == null || !active()) {
			return;
		}
		packet.runUpdates(MotionFeed::onBlockUpdate);
	}

	public static synchronized void forget(long chunkKey) {
		SCORES.forget(chunkKey);
		LOW_SCORES.forget(chunkKey);
		DOOR_OPEN.keySet().removeIf(key -> chunk(key) == chunkKey);
		FURNACE_LIT.keySet().removeIf(key -> chunk(key) == chunkKey);
	}

	public static synchronized void reset() {
		SCORES.clear();
		LOW_SCORES.clear();
		DOOR_OPEN.clear();
		FURNACE_LIT.clear();
	}

	private static void addAt(double x, double y, double z, double weight) {
		if (!active()) {
			return;
		}
		long chunkKey = Chunks.key((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
		add(chunkKey, weight, (int) Math.floor(y));
	}

	private static void add(long chunkKey, double weight, int y) {
		long now = System.currentTimeMillis();
		SCORES.add(chunkKey, weight, now);
		if (y < 0) {
			LOW_SCORES.add(chunkKey, weight, now);
		}
	}

	private static long chunk(BlockPos pos) {
		return Chunks.key(pos.getX() >> 4, pos.getZ() >> 4);
	}

	private static long chunk(long blockKey) {
		return Chunks.key(Chunks.blockX(blockKey) >> 4, Chunks.blockZ(blockKey) >> 4);
	}

	private static Boolean doorOpen(Block block, BlockState state) {
		if (block instanceof DoorBlock && state.hasProperty(DoorBlock.OPEN)) {
			return state.getValue(DoorBlock.OPEN);
		}
		if (block instanceof TrapDoorBlock && state.hasProperty(TrapDoorBlock.OPEN)) {
			return state.getValue(TrapDoorBlock.OPEN);
		}
		if (block instanceof FenceGateBlock && state.hasProperty(FenceGateBlock.OPEN)) {
			return state.getValue(FenceGateBlock.OPEN);
		}
		return null;
	}

	private static boolean containerOrDoor(Block block) {
		return block instanceof AbstractChestBlock<?> || block instanceof BarrelBlock
				|| block instanceof ShulkerBoxBlock || block instanceof DoorBlock
				|| block instanceof TrapDoorBlock || block instanceof FenceGateBlock;
	}
}
