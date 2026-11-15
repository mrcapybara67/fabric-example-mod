package com.capy.client.module.modules.esp;

import com.capy.client.finder.core.Chunks;
import com.capy.client.finder.core.TerrainPlate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Bounded cache of the 8x8 surface heights behind a finder plate.
 *
 * <p>Sampling is deliberately kept out of the render path. A module fills the
 * cache when it flags a chunk and refreshes a sampled chunk at most once every
 * {@link #REFRESH_TICKS} client ticks. The renderer only calls {@link #peek}.</p>
 */
public final class TerrainHeights {
	public static final int MAX_CHUNKS = 512;
	public static final int REFRESH_TICKS = 40;

	@FunctionalInterface
	public interface Sampler {
		/** Surface height at a world column, already clamped by the implementation. */
		int sample(ClientLevel level, int x, int z);
	}

	private record Entry(int[] heights, long sampledTick) {
	}

	private final Sampler sampler;
	private final Map<Long, Entry> cache = new LinkedHashMap<>(64, 0.75f, true);

	public TerrainHeights() {
		this((level, x, z) -> level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
	}

	/** Test seam: a fake sampler can count calls without a client world. */
	public TerrainHeights(Sampler sampler) {
		this.sampler = sampler;
	}

	/**
	 * Returns the current heights, sampling only when the key is absent or the
	 * last sample is at least {@link #REFRESH_TICKS} old.
	 */
	public int[] ensure(ClientLevel level, long key, long gameTime) {
		if (level == null) {
			return null;
		}
		return ensure(level, level.getMinY(), level.getMaxY() - 1, key, gameTime);
	}

	/** Test seam: level may be null when the sampler does not use it. */
	int[] ensure(ClientLevel level, int minY, int maxY, long key, long gameTime) {
		Entry existing = cache.get(key);
		if (existing != null && gameTime >= existing.sampledTick()
				&& gameTime - existing.sampledTick() < REFRESH_TICKS) {
			return existing.heights();
		}
		int[] heights = new int[TerrainPlate.CELLS * TerrainPlate.CELLS];
		int baseX = Chunks.x(key) * 16;
		int baseZ = Chunks.z(key) * 16;
		for (int cellZ = 0; cellZ < TerrainPlate.CELLS; cellZ++) {
			for (int cellX = 0; cellX < TerrainPlate.CELLS; cellX++) {
				int x = baseX + cellX * TerrainPlate.CELL_SIZE + 1;
				int z = baseZ + cellZ * TerrainPlate.CELL_SIZE + 1;
				int height = sampler.sample(level, x, z);
				heights[cellZ * TerrainPlate.CELLS + cellX] = Math.max(minY, Math.min(maxY, height));
			}
		}
		cache.put(key, new Entry(heights, gameTime));
		trim();
		return heights;
	}

	/** Cached heights only; never samples and never touches the world. */
	public int[] peek(long key) {
		Entry entry = cache.get(key);
		return entry == null ? null : entry.heights();
	}

	public void drop(long key) {
		cache.remove(key);
	}

	/** Drops every cached chunk not in {@code keep}. */
	public void retain(Collection<Long> keep) {
		cache.keySet().removeIf(key -> !keep.contains(key));
	}

	public void clear() {
		cache.clear();
	}

	public int size() {
		return cache.size();
	}

	private void trim() {
		while (cache.size() > MAX_CHUNKS) {
			long eldest = cache.keySet().iterator().next();
			cache.remove(eldest);
		}
	}
}
