package com.capy.client.module.modules.esp;

import com.capy.client.config.ConfigValues;
import com.capy.client.finder.core.Chunks;
import com.capy.client.finder.core.ShaftFinder;
import com.capy.client.module.ChoiceSetting;
import com.capy.client.module.SliderSetting;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

/**
 * Read-only vertical shaft finder. It sends no packets and never changes the
 * world.
 *
 * <p>The core looks for a 1x1 column or a 3x1 slot whose sides are closed and
 * whose open run is deep enough. This module supplies the collision-shape map,
 * one chunk at a time, and stops at a wall-clock budget every tick. The flag is
 * the chunk, while the drawn boxes are the exact shaft volumes.</p>
 */
public final class ShaftFinderModule extends FinderModule {
	private static final long SCAN_BUDGET_NANOS = 2_000_000L;
	private static final long RESCAN_INTERVAL_TICKS = 1200L;

	private final SliderSetting minDepth = setting(new SliderSetting("min_depth", 6, 4, 32));
	private final ChoiceSetting shapes = setting(new ChoiceSetting("shapes", 2, "one", "three", "both"));
	private final ArrayDeque<Long> queue = new ArrayDeque<>();
	private final Set<Long> queued = new HashSet<>();
	/**
	 * Each scanned chunk keeps its own result. A shaft can cross a chunk edge,
	 * so the published flags are the union of every source, not the result of
	 * the chunk that happens to be scanned last.
	 */
	private final Map<Long, Map<Long, List<AABB>>> hitsBySource = new HashMap<>();
	private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
	private Job job;
	private long lastEnqueueTick = -1L;

	public ShaftFinderModule() {
		super("shaft_finder", 0xFF62D6B0);
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!beginTick(mc)) {
			return;
		}
		long tick = level.getGameTime();
		pruneUnloadedSources();
		if (lastEnqueueTick < 0L || tick - lastEnqueueTick >= RESCAN_INTERVAL_TICKS) {
			enqueueLoaded();
			lastEnqueueTick = tick;
		}
		long deadline = System.nanoTime() + SCAN_BUDGET_NANOS;
		while (System.nanoTime() < deadline) {
			if (job == null) {
				Long key = queue.pollFirst();
				if (key == null) {
					break;
				}
				queued.remove(key);
				startJob(key);
				if (job == null) {
					continue;
				}
			}
			if (!fillSlice(deadline)) {
				break;
			}
		}
	}

	private void pruneUnloadedSources() {
		var player = Minecraft.getInstance().player;
		if (player == null) {
			return;
		}
		int cx = player.blockPosition().getX() >> 4;
		int cz = player.blockPosition().getZ() >> 4;
		int keep = Math.min(12, simulationDistance() + 4) + 2;
		if (hitsBySource.keySet().removeIf(key -> !loaded(key)
				|| Math.abs(Chunks.x(key) - cx) > keep || Math.abs(Chunks.z(key) - cz) > keep)) {
			rebuildFlags();
		}
	}

	private void enqueueLoaded() {
		int radius = Math.min(12, simulationDistance() + 4);
		var player = Minecraft.getInstance().player;
		if (player == null) {
			return;
		}
		int cx = player.blockPosition().getX() >> 4;
		int cz = player.blockPosition().getZ() >> 4;
		for (int dz = -radius; dz <= radius; dz++) {
			for (int dx = -radius; dx <= radius; dx++) {
				int x = cx + dx;
				int z = cz + dz;
				if (level.getChunkSource().getChunkNow(x, z) == null) {
					continue;
				}
				long key = Chunks.key(x, z);
				if (queued.add(key)) {
					queue.addLast(key);
				}
			}
		}
	}

	private void startJob(long key) {
		LevelChunk chunk = level.getChunkSource().getChunkNow(Chunks.x(key), Chunks.z(key));
		if (chunk == null) {
			return;
		}
		job = new Job(chunk, new ShaftFinder.OpenMap(level.getMinY(), level.getHeight()));
	}

	/** Fills columns until the deadline or the map is complete. */
	private boolean fillSlice(long deadline) {
		Job current = job;
		if (current == null) {
			return false;
		}
		while (System.nanoTime() < deadline) {
			if (current.lx >= ShaftFinder.OpenMap.SIDE) {
				finishJob();
				return true;
			}
			int worldX = current.baseX + current.lx;
			int worldZ = current.baseZ + current.lz;
			for (int y = current.map.minY(); y <= current.map.maxY(); y++) {
				current.map.set(current.lx, y, current.lz, isOpen(current.chunk, worldX, y, worldZ));
			}
			current.lz++;
			if (current.lz >= ShaftFinder.OpenMap.SIDE) {
				current.lz = -ShaftFinder.OpenMap.PAD;
				current.lx++;
			}
		}
		return false;
	}

	private boolean isOpen(LevelChunk chunk, int x, int y, int z) {
		BlockState state;
		int lx = x - chunk.getPos().getMinBlockX();
		int lz = z - chunk.getPos().getMinBlockZ();
		if (lx >= 0 && lx < 16 && lz >= 0 && lz < 16 && y >= chunk.getMinY() && y <= chunk.getMaxY()) {
			int index = (y - chunk.getMinY()) >> 4;
			LevelChunkSection[] sections = chunk.getSections();
			if (index < 0 || index >= sections.length) {
				return true;
			}
			LevelChunkSection section = sections[index];
			state = section == null ? Blocks.AIR.defaultBlockState()
					: section.getBlockState(lx, (y - chunk.getMinY()) & 15, lz);
		} else {
			state = level.getBlockState(probe.set(x, y, z));
		}
		return state.getCollisionShape(level, probe.set(x, y, z)).isEmpty();
	}

	private void finishJob() {
		Job finished = job;
		job = null;
		if (finished == null) {
			return;
		}
		boolean one = !shapes.is("three");
		boolean three = !shapes.is("one");
		List<ShaftFinder.Shaft> shafts = ShaftFinder.find(finished.map, finished.baseX, finished.baseZ,
				minDepth.get(), one, three);
		Map<Long, List<AABB>> found = new HashMap<>();
		for (ShaftFinder.Shaft shaft : shafts) {
			long key = Chunks.key(shaft.x() >> 4, shaft.z() >> 4);
			List<AABB> list = found.computeIfAbsent(key, ignored -> new ArrayList<>());
			if (list.size() >= MAX_FLAGS) {
				continue;
			}
			ShaftFinder.Shape shape = shaft.shape();
			list.add(new AABB(shaft.x(), shaft.minY(), shaft.z(),
					shaft.x() + shape.sizeX, shaft.maxY() + 1.0, shaft.z() + shape.sizeZ));
		}
		long scanned = Chunks.key(finished.chunk.getPos().x, finished.chunk.getPos().z);
		hitsBySource.put(scanned, found);
		rebuildFlags();
	}

	/** Merges every source into one flag set, keeping border boxes. */
	private void rebuildFlags() {
		Map<Long, List<AABB>> union = new HashMap<>();
		for (Map<Long, List<AABB>> found : hitsBySource.values()) {
			for (Map.Entry<Long, List<AABB>> entry : found.entrySet()) {
				List<AABB> target = union.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>());
				for (AABB box : entry.getValue()) {
					if (target.size() >= MAX_FLAGS) {
						break;
					}
					target.add(box);
				}
			}
		}
		replaceFlags(union);
	}

	@Override
	public void resetSession() {
		super.resetSession();
		queue.clear();
		queued.clear();
		hitsBySource.clear();
		job = null;
		lastEnqueueTick = -1L;
	}

	@Override
	public void loadConfig(JsonObject json) {
		super.loadConfig(json);
		minDepth.set(ConfigValues.intValue(json, "min_depth", 6));
		shapes.select(ConfigValues.stringValue(json, "shapes", "both"));
	}

	@Override
	public void saveConfig(JsonObject json) {
		super.saveConfig(json);
		json.addProperty("min_depth", minDepth.get());
		json.addProperty("shapes", shapes.option());
	}

	private static final class Job {
		final LevelChunk chunk;
		final ShaftFinder.OpenMap map;
		final int baseX;
		final int baseZ;
		int lx = -ShaftFinder.OpenMap.PAD;
		int lz = -ShaftFinder.OpenMap.PAD;

		Job(LevelChunk chunk, ShaftFinder.OpenMap map) {
			this.chunk = chunk;
			this.map = map;
			this.baseX = chunk.getPos().getMinBlockX();
			this.baseZ = chunk.getPos().getMinBlockZ();
		}
	}
}
