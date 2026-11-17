package com.capy.client.module.modules.esp;

import com.capy.client.finder.core.BedrockVoid;
import com.capy.client.finder.core.Chunks;
import com.capy.client.finder.core.DrawSink;
import com.capy.client.finder.core.FlagStyles;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

/**
 * Bedrock Void ESP: outlines every cell in the lowest bedrock layer that is
 * not bedrock.
 *
 * <p>The overworld layer is the world floor (Y -64 in vanilla), the nether has
 * both its floor at Y 0 and its roof at Y 127, and the end has no such layer
 * and is skipped. Each chunk is read once per bedrock plane; a rescan replaces
 * the chunk's holes and an unload removes them.</p>
 */
public final class BedrockVoidModule extends FinderModule {
	private static final int NETHER_FLOOR = 0;
	private static final int NETHER_ROOF = 127;

	private final Map<Long, List<AABB>> holesByChunk = new HashMap<>();
	private List<AABB> renderBoxes = List.of();
	private int holeCount;

	public BedrockVoidModule() {
		super("bedrock_void", 0xFFFF6B6B);
	}

	@Override
	public void onTick(Minecraft mc) {
		beginTick(mc);
	}

	@Override
	public boolean scan(ClientLevel level, LevelChunk chunk, long deadlineNanos) {
		this.level = level;
		long key = Chunks.key(chunk.getPos().x, chunk.getPos().z);
		List<Integer> layers = bedrockLayers(level);
		List<AABB> holes = new ArrayList<>();
		int baseX = chunk.getPos().getMinBlockX();
		int baseZ = chunk.getPos().getMinBlockZ();
		int minY = chunk.getMinY();
		LevelChunkSection[] sections = chunk.getSections();
		for (int y : layers) {
			int index = (y - minY) >> 4;
			if (index < 0 || index >= sections.length) {
				continue;
			}
			LevelChunkSection section = sections[index];
			if (section == null) {
				continue;
			}
			boolean[] bedrock = new boolean[256];
			int localY = (y - minY) & 15;
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					bedrock[z * 16 + x] = section.getBlockState(x, localY, z).is(Blocks.BEDROCK);
				}
			}
			for (int[] hole : BedrockVoid.holes(bedrock)) {
				int x = baseX + hole[0];
				int z = baseZ + hole[1];
				holes.add(new AABB(x, y, z, x + 1.0, y + 1.0, z + 1.0));
			}
		}

		if (holes.isEmpty()) {
			holesByChunk.remove(key);
			unmarkFlag(key);
		} else {
			holesByChunk.put(key, List.copyOf(holes));
			markFlag(key, "bedrock void", holes.size(), holes);
		}
		rebuildRender();
		return true;
	}

	@Override
	public List<AABB> boxes() {
		return renderBoxes;
	}

	@Override
	public void drawFlags(DrawSink sink, double timeSec, double timeMs) {
		for (AABB box : boxesNearestFirst()) {
			double distance = boxDistance(box);
			if (!drawableFlag(distance) || !fullStyle(distance, 160)) {
				continue;
			}
			FlagStyles.vortex(sink, (box.minX + box.maxX) * 0.5, box.maxY + 0.02,
					(box.minZ + box.maxZ) * 0.5, colorFor(box, timeSec) & 0xFFFFFF, timeMs);
		}
	}

	private void rebuildRender() {
		List<AABB> all = new ArrayList<>();
		for (List<AABB> holes : holesByChunk.values()) {
			all.addAll(holes);
		}
		renderBoxes = List.copyOf(all);
		holeCount = all.size();
	}

	private static List<Integer> bedrockLayers(ClientLevel level) {
		if (level.dimension() == Level.OVERWORLD) {
			// The overworld floor is the bottom of the level's height, not a
			// constant: datapacks can move it.
			return List.of(level.getMinY());
		}
		if (level.dimension() == Level.NETHER) {
			int floor = Math.max(level.getMinY(), NETHER_FLOOR);
			int roof = Math.min(level.getMaxY() - 1, NETHER_ROOF);
			if (floor > roof) {
				return List.of();
			}
			return floor == roof ? List.of(floor) : List.of(floor, roof);
		}
		return List.of();
	}

	@Override
	protected void onChunkUnloaded(long key) {
		holesByChunk.remove(key);
		rebuildRender();
	}

	@Override
	public void resetSession() {
		super.resetSession();
		holesByChunk.clear();
		renderBoxes = List.of();
		holeCount = 0;
	}
}
