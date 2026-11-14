package com.capy.client.module.modules.esp;

import com.capy.client.config.ConfigValues;
import com.capy.client.finder.core.Chunks;
import com.capy.client.finder.core.ShaftFinder;
import com.capy.client.module.ColorSetting;
import com.capy.client.module.SliderSetting;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.phys.AABB;

/**
 * Tunnel Hole ESP: outlines every closed 1x1 and 3x1 vertical shaft.
 *
 * <p>The shared scanner does the section-by-section open map; this module only
 * turns completed shafts into boxes and keeps one flag per affected chunk. A
 * rescan replaces that chunk's list, and an unload removes it, so a box that
 * is no longer supported disappears instead of lingering.</p>
 */
public final class ShaftFinderModule extends ShaftScanModule {
	private static final int DEFAULT_ONE_COLOR = 0xFFFF5555;
	private static final int DEFAULT_THREE_COLOR = 0xFFFFA033;

	private final SliderSetting minDepth = setting(new SliderSetting("min_depth", 4, 4, 32));
	private final ColorSetting colorThree = setting(new ColorSetting("color_three", DEFAULT_THREE_COLOR));

	public ShaftFinderModule() {
		super("shaft_finder", DEFAULT_ONE_COLOR);
	}

	@Override
	protected int scanDepth() {
		return minDepth.get();
	}

	@Override
	protected void shaftsChanged() {
		Map<Long, List<AABB>> union = new HashMap<>();
		for (List<ShaftFinder.Shaft> shafts : shaftLists()) {
			for (ShaftFinder.Shaft shaft : shafts) {
				long key = Chunks.key(shaft.x() >> 4, shaft.z() >> 4);
				List<AABB> list = union.computeIfAbsent(key, ignored -> new ArrayList<>());
				if (list.size() >= MAX_FLAGS) {
					continue;
				}
				ShaftFinder.Shape shape = shaft.shape();
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
	public int colorFor(AABB box) {
		boolean three = box.maxX - box.minX > 1.5 || box.maxZ - box.minZ > 1.5;
		return three ? colorThree.argb() : color.argb();
	}

	@Override
	public void loadConfig(JsonObject json) {
		super.loadConfig(json);
		minDepth.set(ConfigValues.intValue(json, "min_depth", 4));
		colorThree.set(ConfigValues.intValue(json, "color_three", DEFAULT_THREE_COLOR));
	}

	@Override
	public void saveConfig(JsonObject json) {
		super.saveConfig(json);
		json.addProperty("min_depth", minDepth.get());
		json.addProperty("color_three", colorThree.argb());
	}
}
