package com.capy.client.module.modules.esp;

import com.capy.client.config.ConfigValues;
import com.capy.client.finder.core.Chunks;
import com.capy.client.finder.core.DrawSink;
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
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.chunk.LevelChunk;
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
public abstract class FinderModule extends Module implements Tickable, Configurable, EspBoxSource, FinderScanner.Consumer {
	protected static final int MAX_FLAGS = 256;
	/** Per-finder draw cap, so a crowded scan cannot flood the shared buffer. */
	private static final int MAX_DRAWN_BOXES = 512;
	private static final int MAX_ANNOUNCED = 512;

	/** One reason-bearing finder flag. Score is the finder's own strength hint. */
	public record Flag(long chunkKey, String reason, int score) {
	}

	protected final ColorSetting color;
	protected final Map<Long, Flag> flags = new LinkedHashMap<>();
	protected final Set<Long> announced = new LinkedHashSet<>();
	protected final Map<Long, List<AABB>> boxesByFlag = new LinkedHashMap<>();
	protected final List<AABB> boxes = new ArrayList<>();
	protected ClientLevel level;
	private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
	/** Read by the finder modules when the ClickGUI asks for the elimination map. */
	private static boolean showEliminationMap;

	protected FinderModule(String id, int defaultColor) {
		super(Category.BASE_FINDER, id);
		this.color = setting(new ColorSetting("color", defaultColor));
	}

	/** Chunks currently carrying a flag, in discovery order. */
	public Set<Long> flaggedChunks() {
		return Set.copyOf(flags.keySet());
	}

	/** Immutable snapshot for the HUD, status panel and signal grouping. */
	public List<Flag> flagRecords() {
		return List.copyOf(flags.values());
	}

	public Flag flag(long key) {
		return flags.get(key);
	}

	public int flagCount() {
		return flags.size();
	}

	@Override
	public String info() {
		return flags.isEmpty() ? "" : Integer.toString(flags.size());
	}

	/** Short live status for a finder card and the ClickGUI status panel. */
	public String statusLine() {
		int scanned = FinderScanner.get().scannedCount(this);
		int queued = FinderScanner.get().queueSize();
		String prefix = "scanned " + scanned + ", queue " + queued;
		if (!flags.isEmpty()) {
			return prefix + ", " + flags.size() + (flags.size() == 1 ? " flag" : " flags");
		}
		return prefix + ", no flags - " + statusHint();
	}

	/** What the finder is waiting for, shown only while it has no flags. */
	protected String statusHint() {
		return "waiting for loaded chunks";
	}

	public String displayName() {
		String translated = Component.translatable(translationKey()).getString();
		return translated == null || translated.isEmpty() || translated.equals(translationKey())
				? id()
				: translated;
	}

	@Override
	public String name() {
		return id();
	}

	/** Optional chunk style hook. Shafts keep using their boxes as well. */
	public void drawFlags(DrawSink sink, double timeSec, double timeMs) {
	}

	@Override
	public List<AABB> boxes() {
		return boxes;
	}

	@Override
	public int colorFor(AABB box) {
		return color.argb();
	}

	public int colorArgb() {
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

	protected void markFlag(long key, String reason, int score, List<AABB> newBoxes) {
		String safeReason = reason == null || reason.isBlank() ? "signal" : reason;
		Flag next = new Flag(key, safeReason, score);
		boolean added = !flags.containsKey(key);
		flags.put(key, next);
		if (newBoxes != null) {
			boxesByFlag.put(key, List.copyOf(newBoxes));
		}
		if (added) {
			while (flags.size() > MAX_FLAGS) {
				long eldest = flags.keySet().iterator().next();
				flags.remove(eldest);
				boxesByFlag.remove(eldest);
				announced.remove(eldest);
			}
			announce(next);
		}
		rebuildBoxes();
	}

	protected void markFlag(long key, String reason, int score) {
		markFlag(key, reason, score, List.of());
	}

	protected void unmarkFlag(long key) {
		if (flags.remove(key) != null) {
			boxesByFlag.remove(key);
			rebuildBoxes();
		}
	}

	/** Drops every flag without forgetting which chunks already toasted. */
	protected void clearFlags() {
		flags.clear();
		boxesByFlag.clear();
		boxes.clear();
	}

	/**
	 * Replaces the whole flag set in one step. Used by a finder whose boxes
	 * can span chunk borders, so a scan that finds nothing in its own chunk
	 * cannot erase a neighbour's box. Keys already announced stay announced.
	 */
	protected void replaceFlags(Map<Long, List<AABB>> next, String reason) {
		clearFlags();
		for (Map.Entry<Long, List<AABB>> entry : next.entrySet()) {
			List<AABB> list = entry.getValue();
			markFlag(entry.getKey(), reason, list == null ? 0 : list.size(), list);
		}
	}

	private void rebuildBoxes() {
		boxes.clear();
		for (Map.Entry<Long, Flag> entry : flags.entrySet()) {
			List<AABB> list = boxesByFlag.get(entry.getKey());
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

	/** Surface height at the centre of a flag chunk. */
	public int surfaceY(long key) {
		if (level == null) {
			return 64;
		}
		int cx = Chunks.x(key);
		int cz = Chunks.z(key);
		int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, cx * 16 + 8, cz * 16 + 8);
		return Math.max(level.getMinY(), Math.min(level.getMaxY() - 1, surface));
	}

	/** One 16x16 plate at the surface, used for chunk-level finds. */
	protected AABB surfaceBox(long key) {
		int cx = Chunks.x(key);
		int cz = Chunks.z(key);
		int y = surfaceY(key);
		// getHeight() returns y of the first air block, so y is the ground top,
		// as the atmosphere code also assumes. Keep the plate just above it.
		return new AABB(cx * 16.0 + 0.05, y + 0.01, cz * 16.0 + 0.05,
				cx * 16.0 + 15.95, y + 0.05, cz * 16.0 + 15.95);
	}

	private void announce(Flag flag) {
		long key = flag.chunkKey();
		if (!announced.add(key)) {
			return;
		}
		while (announced.size() > MAX_ANNOUNCED) {
			announced.remove(announced.iterator().next());
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.player == null || mc.getToastManager() == null) {
			return;
		}
		int distance = distanceToChunk(mc, key);
		Guard.run("finder toast", () -> SystemToast.add(mc.getToastManager(),
				SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
				Component.translatable(translationKey()),
				Component.literal(flag.reason() + " at " + Chunks.x(key) + ", " + Chunks.z(key)
						+ " (" + distance + " blocks away)")));
	}

	private static int distanceToChunk(Minecraft mc, long key) {
		double dx = mc.player.getX() - (Chunks.x(key) * 16.0 + 8.0);
		double dz = mc.player.getZ() - (Chunks.z(key) * 16.0 + 8.0);
		return (int) Math.round(Math.sqrt(dx * dx + dz * dz));
	}

	public static boolean eliminationMapVisible() {
		return showEliminationMap;
	}

	public static void setEliminationMapVisible(boolean visible) {
		showEliminationMap = visible;
	}

	public static boolean toggleEliminationMap() {
		showEliminationMap = !showEliminationMap;
		return showEliminationMap;
	}

	public void resetSession() {
		clearFlags();
		announced.clear();
		level = null;
	}

	@Override
	public void unloaded(long key) {
		unmarkFlag(key);
		announced.remove(key);
		onChunkUnloaded(key);
	}

	/** Lets a finder drop the per-chunk bookkeeping behind a flag. */
	protected void onChunkUnloaded(long key) {
	}

	@Override
	public void reset() {
		resetSession();
	}

	@Override
	protected void onToggle(boolean state) {
		if (state) {
			FinderScanner.get().register(this);
		} else {
			FinderScanner.get().unregister(this);
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
