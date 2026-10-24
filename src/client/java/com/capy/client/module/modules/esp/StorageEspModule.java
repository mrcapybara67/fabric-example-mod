package com.capy.client.module.modules.esp;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

/**
 * Storage ESP — a box around every container in range.
 *
 * The scan reads the block entities the client already holds, so it costs one
 * map walk per loaded chunk and never asks the server for anything. It runs
 * once a second rather than once a frame: containers are placed and broken by
 * people, not by the renderer, and a box that is a second late is invisible
 * compared to a scan every frame.
 *
 * A container is anything whose block entity actually stores items, which in
 * vanilla is exactly the block entities that implement {@link Container}:
 * chests, barrels, shulker boxes, furnaces, hoppers, dispensers, brewing
 * stands, pots and bookshelves all do. Ender chests hold their items in the
 * player instead, so they are the one type added back by block id.
 */
public final class StorageEspModule extends EspModule {
	/** Ticks between two scans. */
	private static final int REFRESH_TICKS = 20;
	/** Tiny growth so an outline is not coplanar with the block it wraps. */
	private static final double INFLATE = 0.003;

	private final List<AABB> boxes = new ArrayList<>();
	private final List<AABB> scratch = new ArrayList<>();
	private ClientLevel level;
	private int cooldown;
	private int lastRange = -1;

	public StorageEspModule() {
		super("storage_esp", 0xFFF7A93B, 64);
	}

	@Override
	public List<AABB> boxes() {
		return boxes;
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!isEnabled()) {
			return;
		}
		ClientLevel current = mc.level;
		if (current == null || mc.player == null) {
			level = null;
			boxes.clear();
			scratch.clear();
			cooldown = 0;
			return;
		}
		if (current != level) {
			// A new world: every cached position belongs to the old one.
			level = current;
			boxes.clear();
			scratch.clear();
			cooldown = 0;
		}
		boolean rangeChanged = range() != lastRange;
		if (--cooldown > 0 && !rangeChanged) {
			return;
		}
		lastRange = range();
		cooldown = REFRESH_TICKS;
		scan(current, mc.player.blockPosition());
	}

	private void scan(ClientLevel level, BlockPos center) {
		scratch.clear();
		int limit = range();
		double limitSq = (double) limit * limit;
		double cx = center.getX() + 0.5;
		double cy = center.getY() + 0.5;
		double cz = center.getZ() + 0.5;
		EspScanner.forEachChunk(level, center, limit, chunk -> {
			for (BlockEntity entity : chunk.getBlockEntities().values()) {
				if (!isStorage(entity)) {
					continue;
				}
				BlockPos pos = entity.getBlockPos();
				if (EspScanner.distanceSqr(pos, cx, cy, cz) > limitSq) {
					continue;
				}
				scratch.add(new AABB(pos).inflate(INFLATE));
				if (scratch.size() >= MAX_BOXES) {
					return false;
				}
			}
			return true;
		});
		publish();
	}

	/** Copies the fresh scan into the published list without reallocating it. */
	private void publish() {
		boxes.clear();
		boxes.addAll(scratch);
	}

	private static boolean isStorage(BlockEntity entity) {
		// The ender chest holds its items in the player, so its block entity is
		// not a Container even though it is unmistakably storage.
		return entity instanceof Container || entity.getBlockState().is(Blocks.ENDER_CHEST);
	}

	@Override
	protected void onToggle(boolean state) {
		super.onToggle(state);
		cooldown = 0;
		lastRange = -1;
	}

	@Override
	protected void clear() {
		boxes.clear();
		scratch.clear();
		level = null;
		cooldown = 0;
		lastRange = -1;
	}

	// ---------------------------------------------------------- persistence

	@Override
	public void loadConfig(JsonObject json) {
		super.loadConfig(json);
		// The range may have changed between sessions; scan on the next tick.
		lastRange = -1;
		cooldown = 0;
	}
}
