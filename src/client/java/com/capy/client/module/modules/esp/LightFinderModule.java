package com.capy.client.module.modules.esp;

import com.capy.client.config.ConfigValues;
import com.capy.client.finder.core.Chunks;
import com.capy.client.finder.core.DrawSink;
import com.capy.client.finder.core.FlagLook;
import com.capy.client.finder.core.FlagStyles;
import com.capy.client.module.ColorSetting;
import com.capy.client.module.IntSetting;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Light Finder: one box per underground section that holds block light at or
 * above the minimum. Sections below light eight use the dim colour, sections
 * at eight or above use the bright colour.
 *
 * <p>Every limit in the card is applied to what is drawn: Max Y stops the walk
 * at the requested height, Range trims boxes outside that many chunks, Max
 * Blocks is a hard cap after trimming, and Update Interval sets how often the
 * published set is refreshed from the scanned sections.</p>
 */
public final class LightFinderModule extends FinderModule {
	private static final int BRIGHT_LIGHT = 8;

	private final IntSetting maxY = describe(setting(new IntSetting("max_y", 0, -64, 320, 1)),
			"Do not read or draw light above this Y level.");
	private final IntSetting minLight = describe(setting(new IntSetting("min_light", 1, 1, 15, 1)),
			"The faintest block light that still gets a box.");
	private final IntSetting rangeChunks = describe(setting(new IntSetting("range", 8, 1, 32, 1)),
			"Only draw sections within this many chunks of you.");
	private final IntSetting maxBlocks = describe(setting(new IntSetting("max_blocks", 20000, 100, 100000, 100)),
			"Hard cap on the number of boxes drawn at once.");
	private final IntSetting updateInterval = describe(setting(new IntSetting("update_interval", 20, 1, 200, 1)),
			"Ticks between two light scan passes.");
	private final ColorSetting colorBright = describe(setting(new ColorSetting("color_bright", 0x78FFFF32)),
			"The colour used for sections at light eight or above.");

	private final Map<Long, List<AABB>> boxesBySection = new HashMap<>();
	private final Map<Long, List<AABB>> brightByChunk = new HashMap<>();
	private final Set<AABB> bright = Collections.newSetFromMap(new IdentityHashMap<>());
	private final List<AABB> scratch = new ArrayList<>();
	private final List<AABB> scratchBright = new ArrayList<>();
	private List<AABB> renderBoxes = List.of();
	private boolean scanning;
	private long scanKey = Long.MIN_VALUE;
	private int scanSection;
	private int scanSurface;
	private int scanMax;
	private int sectionCount;
	private long lastPublishTick = Long.MIN_VALUE;

	public LightFinderModule() {
		super("light_finder", 0x50282832);
		useShape("lines");
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!beginTick(mc)) {
			return;
		}
		long tick = level.getGameTime();
		if (lastPublishTick == Long.MIN_VALUE || tick - lastPublishTick >= updateInterval.get()) {
			lastPublishTick = tick;
			rebuildRender();
			FinderScanner.get().queueAllLoaded();
		}
		refreshTerrain(flags.keySet());
	}

	@Override
	public boolean scan(ClientLevel level, LevelChunk chunk, long deadlineNanos) {
		this.level = level;
		long key = Chunks.key(chunk.getPos().x, chunk.getPos().z);
		if (!scanning || scanKey != key) {
			scanning = true;
			scanKey = key;
			scanSection = 0;
			scanMax = 0;
			scratch.clear();
			scratchBright.clear();
			scanSurface = level.getHeight(Heightmap.Types.WORLD_SURFACE,
					chunk.getPos().getMiddleBlockX(), chunk.getPos().getMiddleBlockZ());
		}

		int ceiling = maxY.get();
		int threshold = minLight.get();
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
			if (sectionY + 15 > ceiling || !underground(sectionY, scanSurface)) {
				continue;
			}
			DataLayer data = level.getLightEngine().getLayerListener(LightLayer.BLOCK)
					.getDataLayerData(SectionPos.of(chunk.getPos(), sectionY >> 4));
			if (data == null || data.isDefinitelyFilledWith(0)) {
				continue;
			}
			int max = 0;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						max = Math.max(max, data.get(x, y, z));
					}
				}
			}
			if (max < threshold) {
				continue;
			}
			AABB box = new AABB(baseX, sectionY, baseZ,
					baseX + 16.0, sectionY + 16.0, baseZ + 16.0);
			scratch.add(box);
			if (max >= BRIGHT_LIGHT) {
				scratchBright.add(box);
			}
			scanMax = Math.max(scanMax, max);
		}

		if (scratch.isEmpty()) {
			boxesBySection.remove(key);
			brightByChunk.remove(key);
			unmarkFlag(key);
		} else {
			boxesBySection.put(key, List.copyOf(scratch));
			brightByChunk.put(key, List.copyOf(scratchBright));
			markFlag(key, "section light " + scanMax, scanMax, scratch);
		}
		publishIfDue();
		scanning = false;
		scratch.clear();
		scratchBright.clear();
		return true;
	}

	private void publishIfDue() {
		if (level == null) {
			return;
		}
		long tick = level.getGameTime();
		if (lastPublishTick == Long.MIN_VALUE || tick - lastPublishTick >= updateInterval.get()) {
			lastPublishTick = tick;
			rebuildRender();
		}
	}

	@Override
	public int colorFor(AABB box) {
		return colorFor(box, System.nanoTime() / 1.0e9);
	}

	@Override
	public int colorFor(AABB box, double timeSec) {
		return bright.contains(box) ? colorBright.argbAt(timeSec) : colorArgb(timeSec);
	}

	@Override
	public List<AABB> boxes() {
		return renderBoxes;
	}

	@Override
	public void drawFlags(DrawSink sink, double timeSec, double timeMs) {
		if (flags.isEmpty()) {
			return;
		}
		List<FlagLook.Flag> look = new ArrayList<>(flags.size());
		for (long key : flagsNearestFirst()) {
			AABB core = brightestBox(key);
			double x = core == null ? Chunks.x(key) * 16.0 + 8.0 : (core.minX + core.maxX) * 0.5;
			double z = core == null ? Chunks.z(key) * 16.0 + 8.0 : (core.minZ + core.maxZ) * 0.5;
			int argb = core != null && bright.contains(core) ? colorBright.argbAt(timeSec) : colorArgb(timeSec);
			look.add(FlagLook.Flag.at(key, x, z, terrainOrFlat(key), argb & 0xFFFFFF,
					flagDistance(key), appearEase(key, timeMs), true, true));
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

	private void rebuildRender() {
		Minecraft mc = Minecraft.getInstance();
		boolean trim = mc != null && mc.player != null;
		int playerChunkX = trim ? mc.player.blockPosition().getX() >> 4 : 0;
		int playerChunkZ = trim ? mc.player.blockPosition().getZ() >> 4 : 0;
		int range = rangeChunks.get();
		int cap = maxBlocks.get();
		Set<AABB> included = Collections.newSetFromMap(new IdentityHashMap<>());
		List<AABB> all = new ArrayList<>();
		for (Map.Entry<Long, List<AABB>> entry : boxesBySection.entrySet()) {
			long key = entry.getKey();
			if (trim && (Math.abs(Chunks.x(key) - playerChunkX) > range
					|| Math.abs(Chunks.z(key) - playerChunkZ) > range)) {
				continue;
			}
			for (AABB box : entry.getValue()) {
				if (all.size() >= cap) {
					break;
				}
				all.add(box);
				included.add(box);
			}
			if (all.size() >= cap) {
				break;
			}
		}
		renderBoxes = List.copyOf(all);
		sectionCount = all.size();
		bright.clear();
		for (List<AABB> boxes : brightByChunk.values()) {
			for (AABB box : boxes) {
				if (included.contains(box)) {
					bright.add(box);
				}
			}
		}
	}

	/** True when the section is fully below the surface. */
	private static boolean underground(int sectionY, int surfaceY) {
		return sectionY + 16 <= surfaceY;
	}

	@Override
	protected void onChunkUnloaded(long key) {
		boxesBySection.remove(key);
		brightByChunk.remove(key);
		if (scanning && scanKey == key) {
			clearScan();
		}
		rebuildRender();
	}

	private void clearScan() {
		scanning = false;
		scanKey = Long.MIN_VALUE;
		scanSection = 0;
		scanMax = 0;
		scratch.clear();
		scratchBright.clear();
	}

	@Override
	public void resetSession() {
		super.resetSession();
		boxesBySection.clear();
		brightByChunk.clear();
		bright.clear();
		scratch.clear();
		scratchBright.clear();
		renderBoxes = List.of();
		sectionCount = 0;
		lastPublishTick = Long.MIN_VALUE;
		clearScan();
	}

	@Override
	public void loadConfig(JsonObject json) {
		super.loadConfig(json);
		colorBright.load(json, "color_bright");
		// An old config may hold a range below the new minimum; the generic
		// setting pass has already clamped it, this only records the fact.
		ConfigValues.intValue(json, "range", rangeChunks.get());
	}

	@Override
	public void saveConfig(JsonObject json) {
		super.saveConfig(json);
		colorBright.save(json, "color_bright");
	}
}
