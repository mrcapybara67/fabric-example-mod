package com.capy.client.module.modules.esp;

import com.capy.client.config.ConfigValues;
import com.capy.client.finder.core.Chunks;
import com.capy.client.finder.core.DrawSink;
import com.capy.client.finder.core.FlagLook;
import com.capy.client.finder.core.FlagStyles;
import com.capy.client.finder.core.ShaftFinder;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.ColorSetting;
import com.capy.client.module.IntSetting;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;

/**
 * Tunnel Hole ESP: outlines every closed 1x1 and 3x1 vertical shaft.
 *
 * <p>The shared scanner does the section-by-section open map; this module only
 * turns completed shafts into boxes and keeps one flag per affected chunk. A
 * rescan replaces that chunk's list, and an unload removes it, so a box that
 * is no longer supported disappears instead of lingering.</p>
 *
 * <p>The two shape switches are filters, not decorations: turning 3x1 off
 * drops those shafts from the box list and asks for a fresh scan, because a
 * shaft that was already scanned does not pass through this module again
 * until the scanner revisits its chunk.</p>
 */
public final class ShaftFinderModule extends ShaftScanModule {
	private static final int DEFAULT_ONE_COLOR = 0x96FF5555;
	private static final int DEFAULT_THREE_COLOR = 0x96FFA033;

	private final IntSetting minDepth = describe(setting(new IntSetting("min_depth", 4, 1, 20, 1)),
			"Shortest vertical opening that counts as a tunnel hole.");
	private final BooleanSetting oneByOne = describe(setting(new BooleanSetting("one_by_one", true)),
			"Include the single-block 1x1 shafts.");
	private final BooleanSetting threeByOne = describe(setting(new BooleanSetting("three_by_one", true)),
			"Include the three-block 3x1 shafts.");
	private final ColorSetting colorThree = describe(setting(new ColorSetting("color_three", DEFAULT_THREE_COLOR)),
			"The colour used for the wider 3x1 shafts.");
	private int appliedOne = -1;
	private int appliedThree = -1;

	public ShaftFinderModule() {
		super("shaft_finder", DEFAULT_ONE_COLOR);
		useShape("both");
	}

	@Override
	protected int scanDepth() {
		return minDepth.get();
	}

	@Override
	public void onTick(Minecraft mc) {
		int one = oneByOne.value() ? 1 : 0;
		int three = threeByOne.value() ? 1 : 0;
		if (appliedOne != one || appliedThree != three) {
			appliedOne = one;
			appliedThree = three;
			rescanAll();
		}
		super.onTick(mc);
	}

	@Override
	protected void shaftsChanged() {
		Map<Long, List<AABB>> union = new HashMap<>();
		for (List<ShaftFinder.Shaft> shafts : shaftLists()) {
			for (ShaftFinder.Shaft shaft : shafts) {
				ShaftFinder.Shape shape = shaft.shape();
				boolean wide = shape.sizeX > 1 || shape.sizeZ > 1;
				if (wide ? !threeByOne.value() : !oneByOne.value()) {
					continue;
				}
				long key = Chunks.key(shaft.x() >> 4, shaft.z() >> 4);
				List<AABB> list = union.computeIfAbsent(key, ignored -> new ArrayList<>());
				if (list.size() >= MAX_FLAGS) {
					continue;
				}
				// maxY() is the first closed cell after the run, so it is also
				// the exclusive top of the box. Adding one here would draw the
				// sealing block as part of the shaft.
				list.add(new AABB(shaft.x(), shaft.minY(), shaft.z(),
						shaft.x() + shape.sizeX, shaft.maxY(), shaft.z() + shape.sizeZ));
			}
		}
		replaceFlags(union, "closed vertical shaft");
	}

	@Override
	public void drawFlags(DrawSink sink, double timeSec, double timeMs) {
		List<FlagLook.Flag> look = new ArrayList<>();
		for (AABB box : boxesNearestFirst()) {
			double distance = boxDistance(box);
			if (!drawableFlag(distance)) {
				continue;
			}
			double x = (box.minX + box.maxX) * 0.5;
			double z = (box.minZ + box.maxZ) * 0.5;
			long key = Chunks.key((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
			look.add(FlagLook.Flag.coreOnly(x, box.maxY + 0.05, z,
					colorFor(box, timeSec) & 0xFFFFFF, distance, appearEase(key, timeMs), true));
		}
		FlagLook.drawAll(sink, look, timeMs, quality(), autoSimplified(), true, tracerOrigin());
		for (AABB box : boxesNearestFirst()) {
			double distance = boxDistance(box);
			if (!drawableFlag(distance) || !fullStyle(distance, 140)) {
				continue;
			}
			FlagStyles.depthGauge(sink, (box.minX + box.maxX) * 0.5, (box.minZ + box.maxZ) * 0.5,
					box.maxY, box.minY, colorFor(box, timeSec) & 0xFFFFFF, timeMs);
		}
	}

	@Override
	public void resetSession() {
		super.resetSession();
		appliedOne = -1;
		appliedThree = -1;
	}

	@Override
	public void loadConfig(JsonObject json) {
		super.loadConfig(json);
		minDepth.set(ConfigValues.intValue(json, "min_depth", 4));
		oneByOne.set(ConfigValues.boolValue(json, "one_by_one", true));
		threeByOne.set(ConfigValues.boolValue(json, "three_by_one", true));
		colorThree.load(json, "color_three");
	}

	@Override
	public void saveConfig(JsonObject json) {
		super.saveConfig(json);
		json.addProperty("min_depth", minDepth.get());
		json.addProperty("one_by_one", oneByOne.value());
		json.addProperty("three_by_one", threeByOne.value());
		colorThree.save(json, "color_three");
	}
}
