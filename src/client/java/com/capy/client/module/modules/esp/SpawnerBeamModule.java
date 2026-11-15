package com.capy.client.module.modules.esp;

import com.capy.client.finder.core.ChunkStyles;
import com.capy.client.finder.core.Chunks;
import com.capy.client.finder.core.DrawSink;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Spawner Beam: a thin vertical beam above every mob spawner in the loaded
 * chunks.
 *
 * <p>This reads the chunk's block-entity list, not its blocks: a spawner is
 * already a block entity the client keeps, so there is no palette walk and no
 * cell visit. A chunk rescan replaces its list and an unload removes it.</p>
 */
public final class SpawnerBeamModule extends FinderModule {
	private final Map<Long, List<BlockPos>> spawnersByChunk = new HashMap<>();
	private final List<BlockPos> renderSpawners = new ArrayList<>();

	public SpawnerBeamModule() {
		super("spawner_beam", 0xFFFFA94D);
	}

	/** The scanner registration does the work; no per-tick pass is needed. */
	@Override
	public void onTick(Minecraft mc) {
	}

	@Override
	public boolean scan(ClientLevel level, LevelChunk chunk, long deadlineNanos) {
		this.level = level;
		long key = Chunks.key(chunk.getPos().x, chunk.getPos().z);
		List<BlockPos> found = new ArrayList<>();
		for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
			if (blockEntity != null && blockEntity.getType() == BlockEntityType.MOB_SPAWNER) {
				found.add(blockEntity.getBlockPos().immutable());
			}
		}
		if (found.isEmpty()) {
			spawnersByChunk.remove(key);
		} else {
			spawnersByChunk.put(key, List.copyOf(found));
		}
		rebuildRender();
		return true;
	}

	@Override
	public void drawFlags(DrawSink sink, double timeSec, double timeMs) {
		if (renderSpawners.isEmpty()) {
			return;
		}
		int argb = colorArgb();
		for (BlockPos pos : renderSpawners) {
			double x = pos.getX() + 0.5;
			double z = pos.getZ() + 0.5;
			double bottom = pos.getY() + 1.0;
			ChunkStyles.beam(sink, x, z, bottom, bottom + 96.0, argb);
		}
	}

	private void rebuildRender() {
		renderSpawners.clear();
		for (List<BlockPos> positions : spawnersByChunk.values()) {
			renderSpawners.addAll(positions);
		}
	}

	@Override
	public String statusLine() {
		return "scanned " + FinderScanner.get().scannedCount(this)
				+ ", spawners " + renderSpawners.size();
	}

	@Override
	protected void onChunkUnloaded(long key) {
		spawnersByChunk.remove(key);
		rebuildRender();
	}

	@Override
	public void resetSession() {
		super.resetSession();
		spawnersByChunk.clear();
		renderSpawners.clear();
	}
}
