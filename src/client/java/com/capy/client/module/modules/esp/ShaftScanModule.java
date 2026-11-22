package com.capy.client.module.modules.esp;

import com.capy.client.finder.core.Chunks;
import com.capy.client.finder.core.ShaftFinder;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Shared, read-only shaft scanner for the tunnel-hole and chunk-scanner
 * modules.
 *
 * <p>Only air counts as open. The old filler used an empty collision shape,
 * which also made water, lava, kelp, grass and other pass-through blocks look
 * like a walkable shaft. Those are exactly the natural columns that filled
 * the finder with false flags. An all-air section is open everywhere, a
 * single-state palette answers once, and a mixed section reads its cells
 * directly; there is no collision-shape lookup or per-state cache left.</p>
 *
 * <p>Border cells are read from loaded neighbour chunks. A missing neighbour
 * leaves its cells unknown, which {@link ShaftFinder.OpenMap} treats as open,
 * so a chunk edge cannot invent a shaft. A real border shaft is found when
 * the neighbour is loaded and scanned in its own turn.</p>
 */
public abstract class ShaftScanModule extends FinderModule {
	private final Map<Long, List<ShaftFinder.Shaft>> sourceShafts = new HashMap<>();
	private Job job;
	private int appliedDepth = Integer.MIN_VALUE;

	protected ShaftScanModule(String id, int defaultColor) {
		super(id, defaultColor);
	}

	/** Minimum run length this module asks the finder for. */
	protected abstract int scanDepth();

	/** Called whenever one source chunk's shaft list is replaced or removed. */
	protected abstract void shaftsChanged();

	/** Completed shaft lists, one list per chunk that was scanned. */
	protected final Collection<List<ShaftFinder.Shaft>> shaftLists() {
		return sourceShafts.values();
	}

	/**
	 * Throws away every gathered shaft and asks the shared scanner to walk the
	 * loaded chunks again. A filter toggle changes which shafts are valid, so
	 * the old lists cannot simply be redrawn.
	 */
	protected final void rescanAll() {
		sourceShafts.clear();
		shaftsChanged();
		FinderScanner.get().queueAllLoaded();
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!beginTick(mc)) {
			return;
		}
		int depth = scanDepth();
		if (depth != appliedDepth) {
			// A different depth invalidates every list gathered with the old
			// one. Clear them immediately and ask for a fresh pass rather than
			// leaving boxes from the previous depth on screen.
			appliedDepth = depth;
			sourceShafts.clear();
			shaftsChanged();
			FinderScanner.get().queueAllLoaded();
		}
		pruneUnloadedSources();
		refreshTerrain(flags.keySet());
	}

	@Override
	public final boolean scan(ClientLevel level, LevelChunk chunk, long deadlineNanos) {
		this.level = level;
		long key = Chunks.key(chunk.getPos().x, chunk.getPos().z);
		if (job == null || job.key != key) {
			job = new Job(key, chunk, new ShaftFinder.OpenMap(level.getMinY(), level.getHeight()));
		}
		if (!fillSlice(deadlineNanos)) {
			return false;
		}
		finishJob();
		return true;
	}

	private boolean fillSlice(long deadlineNanos) {
		Job current = job;
		if (current == null) {
			return false;
		}
		LevelChunkSection[] sections = current.chunk.getSections();
		while (current.section < sections.length) {
			// One section is the resumable unit: if the budget ends before this
			// check, the same section is retried next tick. Completing a section
			// is bounded by the 22x22 map footprint.
			if (System.nanoTime() >= deadlineNanos) {
				return false;
			}
			fillSection(current, current.section);
			current.section++;
		}
		return true;
	}

	private void fillSection(Job current, int index) {
		int sectionY = current.map.minY() + (index << 4);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				int mapMinX = Math.max(-ShaftFinder.OpenMap.PAD, dx * 16);
				int mapMaxX = Math.min(15 + ShaftFinder.OpenMap.PAD, dx * 16 + 15);
				int mapMinZ = Math.max(-ShaftFinder.OpenMap.PAD, dz * 16);
				int mapMaxZ = Math.min(15 + ShaftFinder.OpenMap.PAD, dz * 16 + 15);
				LevelChunk source = dx == 0 && dz == 0
						? current.chunk
						: level.getChunkSource().getChunkNow(current.chunk.getPos().x + dx,
								current.chunk.getPos().z + dz);
				if (source == null) {
					setOpen(current.map, mapMinX, mapMaxX, mapMinZ, mapMaxZ, sectionY);
					continue;
				}
				LevelChunkSection[] sections = source.getSections();
				if (index < 0 || index >= sections.length) {
					setOpen(current.map, mapMinX, mapMaxX, mapMinZ, mapMaxZ, sectionY);
					continue;
				}
				LevelChunkSection section = sections[index];
				if (section == null || section.hasOnlyAir()) {
					setOpen(current.map, mapMinX, mapMaxX, mapMinZ, mapMaxZ, sectionY);
					continue;
				}
				BlockState first = section.getStates().get(0, 0, 0);
				boolean single = !section.maybeHas(state -> state != first);
				if (single) {
					boolean open = first != null && first.isAir();
					set(current.map, mapMinX, mapMaxX, mapMinZ, mapMaxZ, sectionY, open);
					continue;
				}
				for (int mapX = mapMinX; mapX <= mapMaxX; mapX++) {
					int localX = mapX - dx * 16;
					for (int mapZ = mapMinZ; mapZ <= mapMaxZ; mapZ++) {
						int localZ = mapZ - dz * 16;
						for (int localY = 0; localY < 16; localY++) {
							int worldY = sectionY + localY;
							if (worldY > current.map.maxY()) {
								break;
							}
							BlockState state = section.getBlockState(localX, localY, localZ);
							current.map.set(mapX, worldY, mapZ, state != null && state.isAir());
						}
					}
				}
			}
		}
	}

	private static void setOpen(ShaftFinder.OpenMap map, int minX, int maxX, int minZ, int maxZ, int sectionY) {
		set(map, minX, maxX, minZ, maxZ, sectionY, true);
	}

	private static void set(ShaftFinder.OpenMap map, int minX, int maxX, int minZ, int maxZ,
			int sectionY, boolean open) {
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				for (int localY = 0; localY < 16; localY++) {
					int y = sectionY + localY;
					if (y > map.maxY()) {
						break;
					}
					map.set(x, y, z, open);
				}
			}
		}
	}

	private void finishJob() {
		Job finished = job;
		job = null;
		if (finished == null) {
			return;
		}
		List<ShaftFinder.Shaft> shafts = ShaftFinder.find(finished.map, finished.baseX, finished.baseZ,
				scanDepth(), true, true);
		sourceShafts.put(finished.key, shafts);
		shaftsChanged();
	}

	private void pruneUnloadedSources() {
		var player = Minecraft.getInstance().player;
		if (player == null || level == null) {
			return;
		}
		int cx = player.blockPosition().getX() >> 4;
		int cz = player.blockPosition().getZ() >> 4;
		int keep = Math.min(12, simulationDistance() + 4) + 2;
		if (sourceShafts.keySet().removeIf(key -> !loaded(key)
				|| Math.abs(Chunks.x(key) - cx) > keep || Math.abs(Chunks.z(key) - cz) > keep)) {
			shaftsChanged();
		}
	}

	@Override
	protected void onChunkUnloaded(long key) {
		boolean removed = sourceShafts.remove(key) != null;
		if (job != null && job.key == key) {
			job = null;
		}
		// Border cells come from the eight neighbours, so an unload can change
		// which shafts are valid in the chunks around it. Re-scan those loaded
		// neighbours instead of leaving their old border boxes on screen.
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx != 0 || dz != 0) {
					FinderScanner.get().requeue(Chunks.key(Chunks.x(key) + dx, Chunks.z(key) + dz));
				}
			}
		}
		if (removed) {
			shaftsChanged();
		}
	}

	@Override
	public void resetSession() {
		super.resetSession();
		sourceShafts.clear();
		job = null;
		appliedDepth = Integer.MIN_VALUE;
	}

	private static final class Job {
		final long key;
		final LevelChunk chunk;
		final ShaftFinder.OpenMap map;
		final int baseX;
		final int baseZ;
		int section;

		Job(long key, LevelChunk chunk, ShaftFinder.OpenMap map) {
			this.key = key;
			this.chunk = chunk;
			this.map = map;
			this.baseX = chunk.getPos().getMinBlockX();
			this.baseZ = chunk.getPos().getMinBlockZ();
		}
	}
}
