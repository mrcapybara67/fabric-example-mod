package com.capy.client.module.modules.esp;

import com.capy.client.config.ConfigValues;
import com.capy.client.finder.core.Chunks;
import com.capy.client.finder.core.DrawSink;
import com.capy.client.finder.core.FlagCost;
import com.capy.client.finder.core.FlagMemory;
import com.capy.client.finder.core.FlagQuality;
import com.capy.client.finder.core.FinderBeams;
import com.capy.client.finder.core.FlagLook;
import com.capy.client.finder.core.FlagRender;
import com.capy.client.finder.core.TerrainPlate;
import com.capy.client.module.Category;
import com.capy.client.module.ChoiceSetting;
import com.capy.client.module.ColorSetting;
import com.capy.client.module.Configurable;
import com.capy.client.module.Module;
import com.capy.client.module.ShapeSetting;
import com.capy.client.module.Tickable;
import com.capy.client.stealth.CapyText;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**
 * Shared, read-only base for the base finders.
 *
 * <p>None of these modules sends a packet, changes a block or interacts with
 * the world. They read only chunks the client already holds. A flag is
 * evidence, never proof. The base owns the shared presentation: a bounded
 * collection of flagged chunks and the outline boxes for those flags. It
 * deliberately has no range, fill, tracer or debug setting: a finder works
 * immediately and costs what the loaded chunks cost.</p>
 */
public abstract class FinderModule extends Module implements Tickable, Configurable, EspBoxSource, FinderScanner.Consumer {
	protected static final int MAX_FLAGS = 256;
	/** Per-finder draw cap, so a crowded scan cannot flood the shared buffer. */
	private static final int MAX_DRAWN_BOXES = 512;

	/** One reason-bearing finder flag. Score is the finder's own strength hint. */
	public record Flag(long chunkKey, String reason, int score) {
	}

	private static final FlagCost FLAG_COST = new FlagCost();

	protected final ColorSetting color;
	/** Set by a subclass that offers a Shape Mode row; null keeps the old look. */
	protected ShapeSetting shapeMode;
	/** Rate-limited toast / chat helper shared by every finder. */
	protected final FinderNotify notify;
	protected final ChoiceSetting flagQuality = describe(setting(new ChoiceSetting("flag_quality", 0,
			"high", "balanced", "low")), "How much animated detail the flags are drawn with.");
	protected final Map<Long, Flag> flags = new LinkedHashMap<>();
	/** Wall-clock time a flag was first added; kept across rescans, absent after a restore. */
	private final Map<Long, Long> appearedAt = new LinkedHashMap<>();
	protected final Map<Long, List<AABB>> boxesByFlag = new LinkedHashMap<>();
	protected final List<AABB> boxes = new ArrayList<>();
	protected final TerrainHeights terrain = new TerrainHeights();

	private final java.util.Set<Long> stale = new LinkedHashSet<>();
	private final java.util.Set<AABB> staleBoxes = Collections.newSetFromMap(new IdentityHashMap<>());
	private static long revision;
	private long memorySaveTicks;
	private int bulkDepth;
	private String sessionDimension;
	private String memoryServer = "singleplayer";
	protected ClientLevel level;
	private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
	/** Read by the finder modules when the ClickGUI asks for the elimination map. */
	private static boolean showEliminationMap;

	protected FinderModule(String id, int defaultColor) {
		this(id, Integer.valueOf(defaultColor));
	}

	/** A finder whose colours belong to individual finds, not to one row. */
	protected FinderModule(String id) {
		this(id, null);
	}

	private FinderModule(String id, Integer defaultColor) {
		super(Category.BASE_FINDER, id);
		this.color = defaultColor == null ? null : describe(setting(new ColorSetting("color", defaultColor)),
				"The colour of this finder's flags.");
		this.notify = new FinderNotify();
	}

	/**
	 * Registers a Shape Mode row and returns it. Call from a subclass
	 * constructor after {@code super(...)}.
	 */
	protected ShapeSetting useShape(String defaultMode) {
		ShapeSetting shape = describe(setting(new ShapeSetting("shape", defaultMode)),
				"How the box is drawn: outline, fill, or both.");
		shape.tip("How boxes are drawn: outline only, filled faces only, or both.");
		this.shapeMode = shape;
		return shape;
	}

	/** A vertical beam above every distinct cluster of flags. */
	protected void drawFlagBeams(DrawSink sink, double height, double width, int argb) {
		int spread = 8;
		for (long key : FinderBeams.coalesce(flagsNearestFirst(), spread)) {
			int[] heights = cachedTerrain(key);
			double bottom = heights == null ? surfaceY(key) + 0.05 : TerrainPlate.average(heights) + 0.05;
			double x = Chunks.x(key) * 16.0 + 8.0;
			double z = Chunks.z(key) * 16.0 + 8.0;
			emitBeam(sink, x, z, bottom, bottom + height, width, argb);
		}
	}

	/**
	 * One beam with a real footprint: thin widths use the classic cross, wider
	 * ones draw a square column so the width setting is visible from above.
	 */
	protected static void emitBeam(DrawSink sink, double x, double z, double bottom, double top,
			double width, int argb) {
		double w = Math.max(0.05, Math.min(1.0, width));
		if (w <= 0.15) {
			com.capy.client.finder.core.ChunkStyles.beam(sink, x, z, bottom, top, argb);
			return;
		}
		double half = w / 2.0;
		sink.line(x - half, bottom, z - half, x - half, top, z - half, 2.0f, argb);
		sink.line(x + half, bottom, z - half, x + half, top, z - half, 2.0f, argb);
		sink.line(x - half, bottom, z + half, x - half, top, z + half, 2.0f, argb);
		sink.line(x + half, bottom, z + half, x + half, top, z + half, 2.0f, argb);
		sink.line(x - half, top, z - half, x + half, top, z - half, 2.0f, argb);
		sink.line(x - half, top, z + half, x + half, top, z + half, 2.0f, argb);
		sink.line(x - half, top, z - half, x - half, top, z + half, 2.0f, argb);
		sink.line(x + half, top, z - half, x + half, top, z + half, 2.0f, argb);
	}

	/** A hue that cycles once every six seconds, keeping the caller's alpha. */
	protected static int rainbowArgb(double timeSec, int alpha) {
		float hue = (float) ((timeSec * 60.0) % 360.0);
		return ColorSetting.toArgb(hue, 1.0f, 1.0f, alpha);
	}

	/** Live, read-only view; callers on the render thread must not mutate it. */
	public Set<Long> flaggedChunks() {
		return Collections.unmodifiableSet(flags.keySet());
	}

	/** Live, read-only collection for the HUD, status panel and signal grouping. */
	public Collection<Flag> flagRecords() {
		return Collections.unmodifiableCollection(flags.values());
	}

	/** Changes whenever any finder's flag set changes; lets renderers cache. */
	public static long flagsRevision() {
		return revision;
	}

	/** Short, honest reliability label for the Finder Status panel. */
	public String reliability() {
		return "hard to hide";
	}

	public Flag flag(long key) {
		return flags.get(key);
	}

	public String displayName() {
		return CapyText.get(translationKey(), id());
	}

	@Override
	public String name() {
		return id();
	}

	/** Optional chunk style hook. Shafts keep using their boxes as well. */
	public void drawFlags(DrawSink sink, double timeSec, double timeMs) {
	}

	/** One standard chunk flag with the shared beacon look. */
	protected void drawSharedChunkFlags(DrawSink sink, double timeSec, double timeMs) {
		boolean tracer = flagTracers();
		List<FlagLook.Flag> look = new ArrayList<>(flags.size());
		for (long key : flagsNearestFirst()) {
			FlagLook.Flag flag = lookFlag(key, sharedFlagColor(key, timeSec), timeMs, tracer);
			if (flag != null) {
				look.add(flag);
			}
		}
		FlagLook.drawAll(sink, look, timeMs, quality(), autoSimplified(), tracer, tracerOrigin());
	}

	protected FlagLook.Flag lookFlag(long key, int argb, double timeMs, boolean tracer) {
		return FlagLook.Flag.chunk(key, terrainOrFlat(key), argb & 0xFFFFFF, flagDistance(key),
				appearEase(key, timeMs), tracer, sharedFlagBeam(), sharedFlagBeamHeight());
	}

	/** Cached terrain, with a flat surface fallback for the first frame after a find. */
	protected int[] terrainOrFlat(long key) {
		int[] heights = cachedTerrain(key);
		if (heights != null) {
			return heights;
		}
		int[] flat = new int[TerrainPlate.CELLS * TerrainPlate.CELLS];
		Arrays.fill(flat, surfaceY(key));
		return flat;
	}

	/** A module with its own ideas about the flag colour can override this. */
	protected int sharedFlagColor(long key, double timeSec) {
		return colorArgb(timeSec);
	}

	/** Modules without a Tracers row draw the shared tracers unconditionally. */
	protected boolean flagTracers() {
		return true;
	}

	/** A module with a Beam row can suppress the beam inside the shared core. */
	protected boolean sharedFlagBeam() {
		return true;
	}

	/** A module with a Beam Height row can size the shared core's beam. */
	protected double sharedFlagBeamHeight() {
		return 60.0;
	}

	/** Ease-out-cubic from the first time this flag was seen; restored flags are fully open. */
	protected double appearEase(long key, double timeMs) {
		Long since = appearedAt.get(key);
		return since == null ? 1.0 : FlagLook.easeOutCubic(since, timeMs);
	}

	/** A point just below the crosshair, or null when there is no client player. */
	protected double[] tracerOrigin() {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.player == null) {
			return null;
		}
		var eye = mc.player.getEyePosition();
		return new double[]{eye.x, eye.y - 0.1, eye.z};
	}

	public FlagQuality quality() {
		return FlagQuality.parse(flagQuality.option());
	}

	public double drawCostMs() {
		return FLAG_COST.moduleMs(id());
	}

	/** Finder Status text: the reliability label plus any automatic lowering. */
	public String statusLabel() {
		String base = reliability();
		return autoSimplified() ? base + " · auto-low" : base;
	}

	public static boolean autoSimplified() {
		return FLAG_COST.simplified();
	}

	public static void beginRenderFrame() {
		FlagRender.beginFrame();
	}

	public static void endRenderFrame(long nowMs) {
		FLAG_COST.endFrame(nowMs);
	}

	public void noteDrawNanos(long nanos) {
		FLAG_COST.add(id(), nanos);
	}

	/** Player distance to a chunk flag's centre, used for level of detail. */
	protected double flagDistance(long key) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return 0.0;
		}
		double dx = Chunks.x(key) * 16.0 + 8.0 - mc.player.getX();
		double dz = Chunks.z(key) * 16.0 + 8.0 - mc.player.getZ();
		return Math.sqrt(dx * dx + dz * dz);
	}

	protected double boxDistance(AABB box) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return 0.0;
		}
		double dx = (box.minX + box.maxX) * 0.5 - mc.player.getX();
		double dy = (box.minY + box.maxY) * 0.5 - mc.player.getY();
		double dz = (box.minZ + box.maxZ) * 0.5 - mc.player.getZ();
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	protected boolean drawableFlag(double distance) {
		return FlagRender.level(distance) != FlagRender.Detail.NONE;
	}

	/**
	 * True only when the saved quality, the distance and the frame budget all
	 * allow the animated style. Failing it leaves the plate and outline drawn.
	 */
	protected boolean fullStyle(double distance, int primitives) {
		return quality() == FlagQuality.HIGH && !autoSimplified()
				&& FlagRender.level(distance) == FlagRender.Detail.FULL
				&& FlagRender.reserve(primitives);
	}

	protected boolean fullStyle(long key, int primitives) {
		return fullStyle(flagDistance(key), primitives);
	}

	/** Nearest first, so the primitive budget reaches the most useful flags. */
	protected List<Long> flagsNearestFirst() {
		List<Long> out = new ArrayList<>(flags.keySet());
		out.sort(Comparator.comparingDouble(this::flagDistance));
		return out;
	}

	protected List<AABB> boxesNearestFirst() {
		List<AABB> out = new ArrayList<>(boxes());
		out.sort(Comparator.comparingDouble(this::boxDistance));
		return out;
	}

	@Override
	public List<AABB> boxes() {
		return boxes;
	}

	@Override
	public int colorFor(AABB box) {
		return colorFor(box, System.nanoTime() / 1.0e9);
	}

	@Override
	public int colorFor(AABB box, double timeSec) {
		if (color == null) {
			return 0xFFFFFFFF;
		}
		int argb = color.argbAt(timeSec);
		if (!staleBoxes.contains(box)) {
			return argb;
		}
		return (Math.max(24, (argb >>> 24) * 45 / 100) << 24) | (argb & 0xFFFFFF);
	}

	public int colorArgb() {
		return colorArgb(System.nanoTime() / 1.0e9);
	}

	public int colorArgb(double timeSec) {
		return color == null ? 0xFFFFFFFF : color.argbAt(timeSec);
	}

	@Override
	public boolean fills() {
		return shapeMode != null && shapeMode.fills();
	}

	@Override
	public boolean outlines() {
		return shapeMode == null || shapeMode.outlines();
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
		String nextDimension = dimensionKey(mc.level);
		if (level != mc.level || !nextDimension.equals(sessionDimension)) {
			saveMemory();
			resetSession();		level = mc.level;
				sessionDimension = nextDimension;
				memoryServer = serverKey(mc);
				restoreMemory(mc);
		}
		if (++memorySaveTicks % 100L == 0L) {
			flushMemory();
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
		if (added) {
			appearedAt.put(key, System.currentTimeMillis());
		}
		if (newBoxes != null) {
			removeStaleBoxes(key);
			boxesByFlag.put(key, List.copyOf(newBoxes));
		}
		stale.remove(key);
		revision++;
		if (added) {
			while (flags.size() > MAX_FLAGS) {
				long eldest = flags.keySet().iterator().next();
				flags.remove(eldest);
				boxesByFlag.remove(eldest);
				appearedAt.remove(eldest);
			}
		}
		if (bulkDepth == 0) {
			rebuildBoxes();
		}
	}

	protected final boolean isStale(long key) {
		return stale.contains(key);
	}

	/** Dims a restored flag until a fresh scan confirms it. */
	protected final int staleColor(long key, int argb) {
		if (!stale.contains(key)) {
			return argb;
		}
		int alpha = Math.max(24, (argb >>> 24) * 45 / 100);
		return (alpha << 24) | (argb & 0xFFFFFF);
	}

	protected static int dimRgb(int rgb) {
		int r = ((rgb >>> 16) & 0xFF) * 55 / 100;
		int g = ((rgb >>> 8) & 0xFF) * 55 / 100;
		int b = (rgb & 0xFF) * 55 / 100;
		return (r << 16) | (g << 8) | b;
	}

	protected void beginBulk() {
		bulkDepth++;
	}

	protected void endBulk() {
		if (bulkDepth > 0) {
			bulkDepth--;
		}
		if (bulkDepth == 0) {
			rebuildBoxes();
		}
	}

	protected void markFlag(long key, String reason, int score) {
		markFlag(key, reason, score, List.of());
	}

	protected void unmarkFlag(long key) {
		if (flags.remove(key) != null) {
			boxesByFlag.remove(key);
			appearedAt.remove(key);
			stale.remove(key);
			removeStaleBoxes(key);
			revision++;
			rebuildBoxes();
		}
	}

	/** Drops every flag and its presentation state. */
	protected void clearFlags() {
		flags.clear();
		appearedAt.clear();
		boxesByFlag.clear();
		boxes.clear();
		stale.clear();
		staleBoxes.clear();
		revision++;
	}

	/**
	 * Replaces the whole flag set in one step. Used by a finder whose boxes
	 * can span chunk borders, so a scan that finds nothing in its own chunk
	 * cannot erase a neighbour's box.
	 */
	protected void replaceFlags(Map<Long, List<AABB>> next, String reason) {
		Set<Long> removed = new LinkedHashSet<>(flags.keySet());
		removed.removeAll(next.keySet());
		for (long key : removed) {
			flags.remove(key);
			boxesByFlag.remove(key);
			stale.remove(key);
			appearedAt.remove(key);
			removeStaleBoxes(key);
		}
		for (Map.Entry<Long, List<AABB>> entry : next.entrySet()) {
			long key = entry.getKey();
			List<AABB> list = entry.getValue();
			boolean added = !flags.containsKey(key);
			Flag flag = new Flag(key, reason == null || reason.isBlank() ? "signal" : reason,
					list == null ? 0 : list.size());
			flags.put(key, flag);
			if (added) {
				appearedAt.put(key, System.currentTimeMillis());
			}
			boxesByFlag.put(key, list == null ? List.of() : List.copyOf(list));
			stale.remove(key);
			removeStaleBoxes(key);
		}
		while (flags.size() > MAX_FLAGS) {
			long eldest = flags.keySet().iterator().next();
			flags.remove(eldest);
			boxesByFlag.remove(eldest);
			appearedAt.remove(eldest);
		}
		revision++;
		rebuildBoxes();
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

	/** Cached terrain only. Never samples, so it is safe in a frame. */
	protected int[] cachedTerrain(long key) {
		return terrain.peek(key);
	}

	/** Samples one chunk outside the render path, at most once every 40 ticks. */
	protected int[] terrainFor(long key) {
		return level == null ? null : terrain.ensure(level, key, level.getGameTime());
	}

	/** Refreshes sampled chunks whose 40-tick window has elapsed. */
	protected void refreshTerrain(Iterable<Long> keys) {
		if (level == null) {
			return;
		}
		long now = level.getGameTime();
		for (long key : keys) {
			terrain.ensure(level, key, now);
		}
	}

	protected void retainTerrain(Collection<Long> keys) {
		terrain.retain(keys);
	}

	protected void dropTerrain(long key) {
		terrain.drop(key);
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
		saveMemory();
		clearFlags();
		stale.clear();
		staleBoxes.clear();
		sessionDimension = null;
		level = null;
		flushMemory();
	}

	private void restoreMemory(Minecraft mc) {
		if (level == null) {
			return;
		}
		Path dir = configDir();
		if (dir == null) {
			return;
		}
		for (FlagMemory.Stored stored : FlagMemory.load(dir, serverKey(mc), dimensionKey(), id())) {
			Flag flag = new Flag(stored.chunkKey(), stored.reason(), stored.score());
			flags.put(flag.chunkKey(), flag);
			AABB box = surfaceBox(flag.chunkKey());
			boxesByFlag.put(flag.chunkKey(), List.of(box));
			stale.add(flag.chunkKey());
			staleBoxes.add(box);
		}
		revision++;
		rebuildBoxes();
	}

	private void saveMemory() {
		if (level == null) {
			return;
		}
		Path dir = configDir();
		if (dir == null) {
			return;
		}
		List<FlagMemory.Stored> values = new ArrayList<>(flags.size());
		for (Flag flag : flags.values()) {
			values.add(new FlagMemory.Stored(flag.chunkKey(), flag.reason(), flag.score()));
		}
		FlagMemory.save(dir, memoryServer, dimensionKey(), id(), values);
	}

	private void flushMemory() {
		Path dir = configDir();
		if (dir != null) {
			FlagMemory.flush(dir);
		}
	}

	private static Path configDir() {
		try {
			return FabricLoader.getInstance().getConfigDir();
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static String serverKey(Minecraft mc) {
		if (mc == null || mc.getCurrentServer() == null) {
			return "singleplayer";
		}
		String ip = mc.getCurrentServer().ip;
		return ip == null || ip.isBlank() ? "unknown" : ip;
	}

	private String dimensionKey() {
		return dimensionKey(level);
	}

	private static String dimensionKey(ClientLevel value) {
		return value == null ? "unknown" : value.dimension().identifier().toString();
	}

	private void removeStaleBoxes(long key) {
		List<AABB> list = boxesByFlag.get(key);
		if (list != null) {
			staleBoxes.removeAll(list);
		}
	}

	@Override
	public void unloaded(long key) {
		unmarkFlag(key);
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
		revision++;
		if (state) {
			FinderScanner.get().register(this);
		} else {
			FinderScanner.get().unregister(this);
			resetSession();
		}
	}

	@Override
	public void loadConfig(JsonObject json) {
		if (color != null) {
			color.load(json, "color");
		}
		flagQuality.select(ConfigValues.stringValue(json, "flag_quality", "high"));
	}

	@Override
	public void saveConfig(JsonObject json) {
		if (color != null) {
			color.save(json, "color");
		}
		json.addProperty("flag_quality", flagQuality.option());
	}
}
