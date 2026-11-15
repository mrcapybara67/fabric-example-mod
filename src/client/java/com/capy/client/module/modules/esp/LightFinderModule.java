package com.capy.client.module.modules.esp;

import com.capy.client.config.ConfigValues;
import com.capy.client.finder.core.Chunks;
import com.capy.client.module.ColorSetting;
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
 * Light Finder: one box per underground section that holds any block light.
 * Sections below light eight use the dim colour, sections at eight or above
 * use the bright colour.
 *
 * <p>Each section's light layer is read once; the section is the resumable
 * unit, so a large view distance is scanned over several client ticks without
 * ever touching the world or sending a packet.</p>
 */
public final class LightFinderModule extends FinderModule {
	private static final int BRIGHT_LIGHT = 8;

	private final ColorSetting colorBright = setting(new ColorSetting("color_bright", 0xFFFFE066));
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

	public LightFinderModule() {
		super("light_finder", 0x995A8CFF);
	}

	@Override
	public void onTick(Minecraft mc) {
		beginTick(mc);
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
			if (max < 1) {
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
		rebuildRender();
		scanning = false;
		scratch.clear();
		scratchBright.clear();
		return true;
	}

	@Override
	public int colorFor(AABB box) {
		return bright.contains(box) ? colorBright.argb() : color.argb();
	}

	@Override
	public List<AABB> boxes() {
		return renderBoxes;
	}

	@Override
	public String statusLine() {
		return "scanned " + FinderScanner.get().scannedCount(this)
				+ ", lit sections " + sectionCount;
	}

	private void rebuildRender() {
		List<AABB> all = new ArrayList<>();
		int sections = 0;
		for (List<AABB> boxes : boxesBySection.values()) {
			all.addAll(boxes);
			sections += boxes.size();
		}
		renderBoxes = List.copyOf(all);
		sectionCount = sections;
		bright.clear();
		for (List<AABB> boxes : brightByChunk.values()) {
			bright.addAll(boxes);
		}
	}

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
		clearScan();
	}

	@Override
	public void loadConfig(JsonObject json) {
		super.loadConfig(json);
		colorBright.set(ConfigValues.intValue(json, "color_bright", 0xFFFFE066));
	}

	@Override
	public void saveConfig(JsonObject json) {
		super.saveConfig(json);
		json.addProperty("color_bright", colorBright.argb());
	}
}
