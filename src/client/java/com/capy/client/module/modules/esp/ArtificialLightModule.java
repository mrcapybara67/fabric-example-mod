package com.capy.client.module.modules.esp;

import com.capy.client.finder.core.Chunks;
import com.capy.client.finder.core.DrawSink;
import com.capy.client.finder.core.FlagLook;
import com.capy.client.finder.core.FlagStyles;
import com.capy.client.finder.core.LightRegions;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.IntSetting;
import com.capy.client.util.Guard;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**
 * Artificial Light ESP: reads block light from the light data layer of every
 * underground section once, keeps the cells at or above five, and groups them
 * into translucent boxes around their brightest cores.
 *
 * <p>Anti-xray cannot hide light: a lit cave, base or tunnel still has a light
 * value on the client even when the server withholds the blocks themselves.
 * The scan is per chunk and resumable per section; the union is built on one
 * worker thread at most once a second, so scanning never stalls the client
 * thread that owns the light engine.</p>
 */
public final class ArtificialLightModule extends FinderModule {
	private static final int MAX_BOXES = 2000;
	private static final long UNION_INTERVAL_NANOS = 1_000_000_000L;

	private final IntSetting renderRadius = describe(setting(new IntSetting("render_radius", 8, 1, 16, 1)),
			"How many chunks around you are read for block light.");
	private final IntSetting minLight = describe(setting(new IntSetting("min_light", 5, 1, 15, 1)),
			"The faintest block light that counts as a lit area.");
	private final BooleanSetting denoise = describe(setting(new BooleanSetting("denoise", true)),
			"Drop one-block light specks that are too small to be a base or tunnel.");

	private final Map<Long, Map<Long, Integer>> cellsByChunk = new HashMap<>();
	private final Map<Long, Integer> scratch = new HashMap<>();
	private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "capy-artificial-light");
		thread.setDaemon(true);
		return thread;
	});

	private volatile long generation;
	private volatile boolean working;
	private volatile Snapshot published = new Snapshot(0L, List.of());
	private Snapshot applied;
	private List<AABB> renderBoxes = List.of();
	private long lastRunNanos;
	private long scanKey = Long.MIN_VALUE;
	private int scanSection;
	private int scanSurface;
	private boolean scanning;
	private int litCellCount;

	public ArtificialLightModule() {
		super("artificial_light", 0x99FFD166);
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!beginTick(mc)) {
			return;
		}
		if (settingsChanged()) {
			applySettings();
		}
		applyPublished();
		refreshTerrain(flags.keySet());
		long now = System.nanoTime();
		if (working || now - lastRunNanos < UNION_INTERVAL_NANOS) {
			return;
		}
		lastRunNanos = now;
		publishUnion(mc);
	}

	@Override
	public boolean scan(ClientLevel level, LevelChunk chunk, long deadlineNanos) {
		this.level = level;
		long key = Chunks.key(chunk.getPos().x, chunk.getPos().z);
		if (!scanning || scanKey != key) {
			scanning = true;
			scanKey = key;
			scanSection = 0;
			scratch.clear();
			scanSurface = level.getHeight(Heightmap.Types.WORLD_SURFACE,
					chunk.getPos().getMiddleBlockX(), chunk.getPos().getMiddleBlockZ());
		}

		LevelChunkSection[] sections = chunk.getSections();
		int minY = chunk.getMinY();
		int baseX = chunk.getPos().getMinBlockX();
		int baseZ = chunk.getPos().getMinBlockZ();
		for (int index = scanSection; index < sections.length; index++) {
			if (System.nanoTime() >= deadlineNanos) {
				scanSection = index;
				return false;
			}
			scanSection = index + 1;
			int sectionY = minY + (index << 4);
			if (!underground(sectionY, scanSurface)) {
				continue;
			}
			// One data-layer read per section. Reading every cell through the
			// light engine would resolve the same section thousands of times.
			DataLayer data = level.getLightEngine().getLayerListener(LightLayer.BLOCK)
					.getDataLayerData(SectionPos.of(chunk.getPos(), sectionY >> 4));
			if (data == null || data.isDefinitelyFilledWith(0)) {
				continue;
			}
			for (int y = 0; y < 16; y++) {
				int worldY = sectionY + y;
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						int light = data.get(x, y, z);
						if (light >= minLight.get()) {
							scratch.put(Chunks.block(baseX + x, worldY, baseZ + z), light);
						}
					}
				}
			}
		}

		Map<Long, Integer> previous = cellsByChunk.put(key, Map.copyOf(scratch));
		litCellCount += scratch.size() - (previous == null ? 0 : previous.size());
		scanning = false;
		scratch.clear();
		return true;
	}

	@Override
	protected void onChunkUnloaded(long key) {
		Map<Long, Integer> removed = cellsByChunk.remove(key);
		if (removed != null) {
			litCellCount -= removed.size();
		}
		if (scanning && scanKey == key) {
			scanning = false;
			scratch.clear();
		}
		// The union is stale now; let the next tick build a fresh one.
		lastRunNanos = 0L;
	}

	@Override
	public List<AABB> boxes() {
		return renderBoxes;
	}

	@Override
	public boolean fills() {
		return true;
	}

	@Override
	public void drawFlags(DrawSink sink, double timeSec, double timeMs) {
		if (flags.isEmpty()) {
			return;
		}
		List<FlagLook.Flag> look = new ArrayList<>(flags.size());
		int rgb = colorArgb(timeSec) & 0xFFFFFF;
		for (long key : flagsNearestFirst()) {
			AABB core = brightestBox(key);
			double x = core == null ? Chunks.x(key) * 16.0 + 8.0 : (core.minX + core.maxX) * 0.5;
			double z = core == null ? Chunks.z(key) * 16.0 + 8.0 : (core.minZ + core.maxZ) * 0.5;
			look.add(FlagLook.Flag.at(key, x, z, terrainOrFlat(key), rgb, flagDistance(key),
					appearEase(key, timeMs), true, true));
		}
		FlagLook.drawAll(sink, look, timeMs, quality(), autoSimplified(), true, tracerOrigin());
	}

	private AABB brightestBox(long key) {
		AABB best = null;
		for (AABB box : boxesByFlag.getOrDefault(key, List.of())) {
			if (best == null || box.maxY > best.maxY) {
				best = box;
			}
		}
		return best;
	}

	private void publishUnion(Minecraft mc) {
		if (cellsByChunk.isEmpty()) {
			published = new Snapshot(generation, List.of());
			return;
		}
		int playerX = mc.player.blockPosition().getX() >> 4;
		int playerZ = mc.player.blockPosition().getZ() >> 4;
		List<Map<Long, Integer>> snapshot = new ArrayList<>();
		for (Map.Entry<Long, Map<Long, Integer>> entry : cellsByChunk.entrySet()) {
			long key = entry.getKey();
			int radius = renderRadius.get();
			if (Math.abs(Chunks.x(key) - playerX) <= radius
					&& Math.abs(Chunks.z(key) - playerZ) <= radius) {
				// The per-chunk maps are immutable, so the worker can read them
				// while the client thread rescans a chunk and replaces its map.
				snapshot.add(entry.getValue());
			}
		}
		long gen = generation;
		working = true;
		try {
			worker.execute(() -> {
				try {
					Map<Long, Integer> union = new HashMap<>();
					for (Map<Long, Integer> cells : snapshot) {
						union.putAll(cells);
					}
					List<LightRegions.Box> boxes = LightRegions.group(union, minLight.get(), MAX_BOXES);
					if (gen == generation) {
						published = new Snapshot(gen, List.copyOf(boxes));
					}
				} catch (Throwable failure) {
					Guard.report("artificial light union", failure);
				} finally {
					working = false;
				}
			});
		} catch (RuntimeException failure) {
			working = false;
			Guard.report("artificial light worker", failure);
		}
	}

	private void applyPublished() {
		Snapshot next = published;
		if (next == applied) {
			return;
		}
		if (next.generation() != generation) {
			return;
		}
		applied = next;
		if (next.boxes().isEmpty()) {
			clearFlags();
			renderBoxes = List.of();
			return;
		}

		Map<Long, List<AABB>> byChunk = new LinkedHashMap<>();
		Map<Long, Integer> peaks = new HashMap<>();
		List<AABB> all = new ArrayList<>(next.boxes().size());
		for (LightRegions.Box found : next.boxes()) {
			// Denoise drops a lit area that spans fewer than three blocks in
			// total: a single torch on a wall is light, not a place.
			int extent = (found.maxX() - found.minX()) + (found.maxY() - found.minY())
					+ (found.maxZ() - found.minZ());
			if (denoise.value() && extent < 3) {
				continue;
			}
			AABB box = new AABB(found.minX(), found.minY(), found.minZ(),
					found.maxX(), found.maxY(), found.maxZ());
			int cx = Math.floorDiv(found.minX() + found.maxX() - 1, 2) >> 4;
			int cz = Math.floorDiv(found.minZ() + found.maxZ() - 1, 2) >> 4;
			long key = Chunks.key(cx, cz);
			byChunk.computeIfAbsent(key, ignored -> new ArrayList<>()).add(box);
			peaks.merge(key, found.peak(), Math::max);
			all.add(box);
		}

		clearFlags();
		for (Map.Entry<Long, List<AABB>> entry : byChunk.entrySet()) {
			long key = entry.getKey();
			int peak = peaks.getOrDefault(key, minLight.get());
			markFlag(key, "lit area, light " + peak, peak, entry.getValue());
		}
		renderBoxes = List.copyOf(all);
	}

	private static boolean underground(int sectionY, int surfaceY) {
		return sectionY + 16 <= surfaceY;
	}

	private int appliedMinLight = -1;
	private int appliedRadius = -1;
	private boolean appliedDenoise;

	private boolean settingsChanged() {
		return appliedMinLight != minLight.get() || appliedRadius != renderRadius.get()
				|| appliedDenoise != denoise.value();
	}

	/** A different threshold makes every cached cell a claim about another rule. */
	private void applySettings() {
		appliedMinLight = minLight.get();
		appliedRadius = renderRadius.get();
		appliedDenoise = denoise.value();
		generation++;
		cellsByChunk.clear();
		scratch.clear();
		litCellCount = 0;
		scanning = false;
		scanKey = Long.MIN_VALUE;
		scanSection = 0;
		lastRunNanos = 0L;
		applied = null;
		renderBoxes = List.of();
		published = new Snapshot(generation, List.of());
		com.capy.client.module.modules.esp.FinderScanner.get().queueAllLoaded();
	}

	@Override
	public void resetSession() {
		super.resetSession();
		generation++;
		cellsByChunk.clear();
		scratch.clear();
		litCellCount = 0;
		scanning = false;
		scanKey = Long.MIN_VALUE;
		scanSection = 0;
		lastRunNanos = 0L;
		applied = null;
		renderBoxes = List.of();
		published = new Snapshot(generation, List.of());
	}

	private record Snapshot(long generation, List<LightRegions.Box> boxes) {
	}
}
