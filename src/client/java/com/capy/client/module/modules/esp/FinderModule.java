package com.capy.client.module.modules.esp;

import com.capy.client.config.ConfigValues;
import com.capy.client.finder.core.Chunks;
import com.capy.client.module.Category;
import com.capy.client.module.ColorSetting;
import com.capy.client.module.Configurable;
import com.capy.client.module.Module;
import com.capy.client.module.Tickable;
import com.capy.client.util.Guard;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**
 * Shared, read-only base for the base finders.
 *
 * <p>None of these modules sends a packet, changes a block or interacts with
 * the world. They read only chunks the client already holds. A flag is
 * evidence, never proof. The base owns the shared presentation: a bounded
 * collection of flagged chunks, the outline boxes for those flags, and one
 * toast the first time a flag appears. It deliberately has no range, fill,
 * tracer or debug setting: a finder works immediately and costs what the
 * loaded chunks cost.</p>
 */
public abstract class FinderModule extends Module implements Tickable, Configurable, EspBoxSource {
	protected static final int MAX_FLAGS = 256;
	/** Per-finder draw cap, so a crowded scan cannot flood the shared buffer. */
	private static final int MAX_DRAWN_BOXES = 512;
	private static final int MAX_ANNOUNCED = 512;

	protected final ColorSetting color;
	protected final Set<Long> flags = new LinkedHashSet<>();
	protected final Set<Long> announced = new LinkedHashSet<>();
	protected final Map<Long, List<AABB>> boxesByFlag = new LinkedHashMap<>();
	protected final List<AABB> boxes = new ArrayList<>();
	protected ClientLevel level;
	private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

	protected FinderModule(String id, int defaultColor) {
		super(Category.BASE_FINDER, id);
		this.color = setting(new ColorSetting("color", defaultColor));
	}

	/** Chunks currently carrying a flag, in discovery order. */
	public Set<Long> flaggedChunks() {
		return Set.copyOf(flags);
	}

	public int flagCount() {
		return flags.size();
	}

	@Override
	public String info() {
		return flags.isEmpty() ? "" : Integer.toString(flags.size());
	}

	@Override
	public List<AABB> boxes() {
		return boxes;
	}

	@Override
	public int colorFor(AABB box) {
		return color.argb();
	}

	@Override
	public boolean fills() {
		return false;
	}

	@Override
	public boolean outlines() {
		return true;
	}

	@Override
	public boolean tracers() {
		return false;
	}

	@Override
	public float boxWidth() {
		return 1.15f;
	}

	@Override
	public float tracerWidth() {
		return 1.15f;
	}

	/** True when a tick may scan; also resets every finder on a world change. */
	protected boolean beginTick(Minecraft mc) {
		if (!isEnabled()) {
			return false;
		}
		if (mc == null || mc.player == null || mc.level == null) {
			if (level != null) {
				resetSession();
			}
			return false;
		}
		if (level != mc.level) {
			resetSession();
			level = mc.level;
		}
		return true;
	}

	protected int simulationDistance() {
		if (level == null) {
			return 8;
		}
		int distance = level.getServerSimulationDistance();
		return Math.max(2, Math.min(32, distance <= 0 ? 8 : distance));
	}

	protected boolean loaded(long key) {
		return level != null && level.getChunkSource().getChunkNow(Chunks.x(key), Chunks.z(key)) != null;
	}

	protected void markFlag(long key, List<AABB> newBoxes) {
		boolean added = flags.add(key);
		if (newBoxes != null) {
			boxesByFlag.put(key, List.copyOf(newBoxes));
		}
		if (added) {
			while (flags.size() > MAX_FLAGS) {
				long eldest = flags.iterator().next();
				flags.remove(eldest);
				boxesByFlag.remove(eldest);
				announced.remove(eldest);
			}
			announce(key);
		}
		rebuildBoxes();
	}

	protected void markFlag(long key) {
		markFlag(key, List.of());
	}

	protected void unmarkFlag(long key) {
		if (flags.remove(key)) {
			boxesByFlag.remove(key);
			rebuildBoxes();
		}
	}

	/**
	 * Replaces the whole flag set in one step. Used by a finder whose boxes
	 * can span chunk borders, so a scan that finds nothing in its own chunk
	 * cannot erase a neighbour's box. Keys already announced stay announced.
	 */
	protected void replaceFlags(Map<Long, List<AABB>> next) {
		flags.clear();
		boxesByFlag.clear();
		boxes.clear();
		for (Map.Entry<Long, List<AABB>> entry : next.entrySet()) {
			markFlag(entry.getKey(), entry.getValue());
		}
	}

	private void rebuildBoxes() {
		boxes.clear();
		for (long key : flags) {
			List<AABB> list = boxesByFlag.get(key);
			if (list == null || list.isEmpty()) {
				continue;
			}
			for (AABB box : list) {
				if (boxes.size() >= MAX_DRAWN_BOXES) {
					return;
				}
				boxes.add(box);
			}
		}
	}

	/** One 16x16 plate at the surface, used for chunk-level finds. */
	protected AABB surfaceBox(long key) {
		int cx = Chunks.x(key);
		int cz = Chunks.z(key);
		int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, cx * 16 + 8, cz * 16 + 8);
		int y = Math.max(level.getMinY(), Math.min(level.getMaxY() - 1, surface));
		// getHeight() returns y of the first air block, so y is the ground top,
		// as the atmosphere code also assumes. Keep the plate just above it.
		return new AABB(cx * 16.0 + 0.05, y + 0.01, cz * 16.0 + 0.05,
				cx * 16.0 + 15.95, y + 0.05, cz * 16.0 + 15.95);
	}

	private void announce(long key) {
		if (!announced.add(key)) {
			return;
		}
		while (announced.size() > MAX_ANNOUNCED) {
			announced.remove(announced.iterator().next());
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.getToastManager() == null) {
			return;
		}
		Guard.run("finder toast", () -> SystemToast.add(mc.getToastManager(),
				SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
				Component.literal("Capy finder"),
				Component.literal("Chunk " + Chunks.x(key) + ", " + Chunks.z(key))));
	}

	public void resetSession() {
		flags.clear();
		announced.clear();
		boxesByFlag.clear();
		boxes.clear();
		level = null;
	}

	@Override
	protected void onToggle(boolean state) {
		if (!state) {
			resetSession();
		}
	}

	@Override
	public void loadConfig(JsonObject json) {
		color.set(ConfigValues.intValue(json, "color", color.get()));
	}

	@Override
	public void saveConfig(JsonObject json) {
		json.addProperty("color", color.argb());
	}
}
