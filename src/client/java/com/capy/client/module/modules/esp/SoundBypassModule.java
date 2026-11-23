package com.capy.client.module.modules.esp;

import com.capy.client.config.ConfigValues;
import com.capy.client.finder.core.ActivityGrid;
import com.capy.client.finder.core.ChannelRecord;
import com.capy.client.finder.core.ChannelStats;
import com.capy.client.finder.core.Chunks;
import com.capy.client.finder.core.DrawSink;
import com.capy.client.finder.core.FinderBeams;
import com.capy.client.finder.core.FinderRules;
import com.capy.client.finder.core.FlagLook;
import com.capy.client.finder.core.FlagStyles;
import com.capy.client.finder.core.SoundBypassFeed;
import com.capy.client.finder.core.SoundKinds;
import com.capy.client.finder.core.TerrainPlate;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.ColorSetting;
import com.capy.client.module.IntSetting;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

/**
 * Sound Bypass: marks player or machine activity from packets the server has
 * already sent. It never scans chunks and never sends anything, so it can only
 * mark what the server actually delivers.
 */
public final class SoundBypassModule extends FinderModule {
	private static final double MAX_DISTANCE = 192.0;
	private static final int MAX_CELLS = 8;
	private static final int MAX_DRAWN = 6;
	private static final long REFRESH_MS = 500L;
	private static final long SNAPSHOT_TICKS = 5L;

	private final BooleanSetting colorByType = describe(setting(new BooleanSetting("color_by_type", false)),
			"Use a colour per sound kind instead of the overlay colour.");
	private final IntSetting sensitivity = describe(setting(new IntSetting("sensitivity", 12, 2, 80, 1)),
			"The activity score a cell must reach to be marked.");
	private final IntSetting memory = describe(setting(new IntSetting("memory", 60, 10, 600, 1)),
			"How long activity is remembered, in seconds.");
	private final IntSetting ignoreNearMe = describe(setting(new IntSetting("ignore_near_me", 24, 0, 96, 1)),
			"Drop activity closer than this many blocks to you.");
	private final BooleanSetting notifications = describe(setting(new BooleanSetting("notify", false)),
			"Show a toast when a new activity cell appears.");
	private final IntSetting cooldown = describe(setting(new IntSetting("cooldown", 120, 10, 900, 1),
			() -> notifications.value()), "Seconds between two activity toasts.");
	private final BooleanSetting chatMessage = describe(setting(new BooleanSetting("chat_message", false),
			() -> notifications.value()), "Write one chat line per new activity cell.");
	private final BooleanSetting beams = describe(setting(new BooleanSetting("beams", true)),
			"Draw a vertical beam above active cells.");
	private final BooleanSetting tracer = describe(setting(new BooleanSetting("tracers", false)),
			"Draw a fading line from the crosshair to every active cell.");
	private final ColorSetting colorBusy = describe(setting(new ColorSetting("color_busy", 0xE6FF5078)),
			"The colour for cells with twice the threshold score.");
	private long lastNotifyMs = Long.MIN_VALUE / 2;
	private List<AABB> tracerBoxes = List.of();
	private final SoundBypassFeed feed = SoundBypassFeed.get();
	private final List<CellView> active = new ArrayList<>();
	private final Map<Long, Long> activeSince = new java.util.HashMap<>();
	private final Set<String> savedDeep = new HashSet<>();

	private record CellView(ActivityGrid.Cell cell, long chunkKey, String reason) {
	}

	private int shown;
	private long lastRefreshMs = -1L;
	private long lastSnapshotTick = Long.MIN_VALUE;
	private Path configDir;
	private String server = "singleplayer";
	private String savedServer;

	public SoundBypassModule() {
		super("sound_bypass", 0xC878C8FF);
	}

	@Override
	public boolean fills() {
		return false;
	}

	@Override
	public boolean outlines() {
		return false;
	}

	@Override
	public boolean tracers() {
		return false;
	}

	@Override
	protected boolean flagTracers() {
		return tracer.value();
	}

	@Override
	public boolean tracerFade() {
		return tracer.value();
	}

	@Override
	public List<AABB> boxes() {
		return tracerBoxes;
	}

	@Override
	public int colorFor(AABB box, double timeSec) {
		return colorArgb(timeSec);
	}

	/** Packet driven; the chunk scanner is deliberately not used. */
	@Override
	public boolean scan(ClientLevel level, LevelChunk chunk, long deadlineNanos) {
		return true;
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!beginTick(mc)) {
			return;
		}
		long gameTime = mc.level.getGameTime();
		if (lastSnapshotTick == Long.MIN_VALUE || gameTime - lastSnapshotTick >= SNAPSHOT_TICKS) {
			lastSnapshotTick = gameTime;
			refreshSnapshot(mc);
		}
		long now = System.currentTimeMillis();
		if (lastRefreshMs >= 0L && now - lastRefreshMs < REFRESH_MS) {
			return;
		}
		lastRefreshMs = now;
		refreshCells(mc, now);
		refreshChannels(mc);
	}

	@Override
	public void drawFlags(DrawSink sink, double timeSec, double timeMs) {
		if (active.isEmpty() || level == null) {
			return;
		}
		List<CellView> ordered = new ArrayList<>(active.subList(0, shown));
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			ordered.sort(Comparator.comparingDouble(view -> mc.player.distanceToSqr(
					view.cell().x(), view.cell().y(), view.cell().z())));
		}
		Set<Long> drawn = new HashSet<>();
		List<FlagLook.Flag> look = new ArrayList<>();
		for (CellView view : ordered) {
			long key = view.chunkKey();
			if (!drawn.add(key)) {
				continue;
			}
			ActivityGrid.Cell cell = view.cell();
			double distance = flagDistance(key);
			if (!drawableFlag(distance)) {
				continue;
			}
			double fade = FinderRules.scoreFade(cell.score(), sensitivity.get());
			if (fade <= 0.01) {
				continue;
			}
			int base = cell.score() >= sensitivity.get() * 2.0 ? colorBusy.argbAt(timeSec)
					: colorByType.value() ? kindColor(cell.dominant()) : colorArgb(timeSec);
			if (((base >>> 24) & 0xFF) * fade <= 1.0) {
				continue;
			}
			double ease = FlagLook.easeOutCubic(activeSince.getOrDefault(key, 0L), timeMs);
			int[] heights = terrainOrFlat(key);
			double surface = TerrainPlate.average(heights);
			look.add(FlagLook.Flag.atDepth(key, cell.x(), cell.y() + 0.03, cell.z(), heights,
					base & 0xFFFFFF, distance, ease, tracer.value(), beams.value()));
			if (Double.isFinite(surface) && surface > cell.y() + 2.0) {
				FlagStyles.depthGauge(sink, cell.x(), cell.z(), surface + 0.06, cell.y(),
						base & 0xFFFFFF, timeMs);
			}
		}
		FlagLook.drawAll(sink, look, timeMs, quality(), autoSimplified(), tracer.value(), tracerOrigin());
	}

	private void refreshSnapshot(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			return;
		}
		Player self = mc.player;
		List<double[]> positions = new ArrayList<>();
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity == self || entity.isRemoved() || entity.isInvisible() || !(entity instanceof LivingEntity)) {
				continue;
			}
			if (entity instanceof Player player && player.isSpectator()) {
				continue;
			}
			if (entity.distanceToSqr(self) > MAX_DISTANCE * MAX_DISTANCE) {
				continue;
			}
			positions.add(new double[]{entity.getX(), entity.getY(), entity.getZ()});
		}
		double[] flat = new double[positions.size() * 3];
		for (int i = 0; i < positions.size(); i++) {
			double[] position = positions.get(i);
			flat[i * 3] = position[0];
			flat[i * 3 + 1] = position[1];
			flat[i * 3 + 2] = position[2];
		}
		feed.refreshSnapshot(self.getX(), self.getY(), self.getZ(), flat);
	}

	private void refreshCells(Minecraft mc, long now) {
		double halfLife = FinderRules.halfLifeForWindowSeconds(memory.get());
		List<ActivityGrid.Cell> top = feed.top(now, sensitivity.get(), MAX_CELLS, halfLife);
		List<CellView> next = new ArrayList<>();
		Set<Long> keys = new LinkedHashSet<>();
		Set<Long> before = new HashSet<>();
		for (CellView view : active) {
			before.add(view.chunkKey());
		}
		double px = mc.player == null ? 0 : mc.player.getX();
		double py = mc.player == null ? 0 : mc.player.getY();
		double pz = mc.player == null ? 0 : mc.player.getZ();
		for (ActivityGrid.Cell cell : top) {
			double dx = cell.x() - px;
			double dy = cell.y() - py;
			double dz = cell.z() - pz;
			if (dx * dx + dy * dy + dz * dz > MAX_DISTANCE * MAX_DISTANCE) {
				continue;
			}
			long key = Chunks.key(cell.sx(), cell.sz());
			if (FinderRules.withinBlocks3D(cell.x(), cell.y(), cell.z(), px, py, pz, ignoreNearMe.get())) {
				continue;
			}
			next.add(new CellView(cell, key, reason(cell)));
			keys.add(key);
		}
		boolean announced = false;
		for (CellView view : next) {
			if (before.contains(view.chunkKey())) {
				continue;
			}
			if (chatMessage.value()) {
				notify.chat(mc, "Sound Bypass: " + view.reason());
			}
			if (notifications.value() && !announced
					&& now - lastNotifyMs >= cooldown.get() * 1000L) {
				announced = true;
				if (notify.toast(mc, "Sound Bypass", view.reason())) {
					lastNotifyMs = now;
				}
			}
		}
		for (long key : keys) {
			activeSince.putIfAbsent(key, now);
		}
		activeSince.keySet().retainAll(keys);
		active.clear();
		active.addAll(next);
		shown = Math.min(MAX_DRAWN, active.size());
		List<AABB> boxes = new ArrayList<>(next.size());
		for (CellView view : next) {
			ActivityGrid.Cell cell = view.cell();
			boxes.add(new AABB(cell.x() - 0.5, cell.y() - 0.5, cell.z() - 0.5,
					cell.x() + 0.5, cell.y() + 0.5, cell.z() + 0.5));
		}
		tracerBoxes = List.copyOf(boxes);
		refreshTerrain(keys);
		retainTerrain(keys);
	}

	private void refreshChannels(Minecraft mc) {
		Path dir = configDir();
		if (dir == null) {
			return;
		}
		configDir = dir;
		server = serverKey(mc);
		if (!server.equals(savedServer)) {
			savedServer = server;
			savedDeep.clear();
			savedDeep.addAll(ChannelRecord.deepChannels(dir, server));
		}
		boolean changed = false;
		for (ChannelStats.Channel channel : ChannelStats.Channel.values()) {
			String name = channel.name().toLowerCase(Locale.ROOT);
			if (feed.stats().deep(channel) > 0 && !savedDeep.contains(name)) {
				ChannelRecord.note(dir, server, channel);
				savedDeep.add(name);
				changed = true;
			}
		}
		if (changed) {
			ChannelRecord.flush(dir);
		}
	}

	private static String reason(ActivityGrid.Cell cell) {
		String kind = cell.dominant().name().toLowerCase(Locale.ROOT);
		String reason = kind + " activity, Y " + (int) Math.floor(cell.y());
		if (cell.farm()) {
			long seconds = Math.max(1L, Math.round(cell.periodMs() / 1000.0));
			reason += " (farm, every " + seconds + " s)";
		}
		return reason;
	}

	@Override
	public String reliability() {
		return "reads sent packets only";
	}

	@Override
	protected void onToggle(boolean state) {
		if (state) {
			feed.acquire();
			lastRefreshMs = -1L;
			lastSnapshotTick = Long.MIN_VALUE;
			Minecraft mc = Minecraft.getInstance();
			configDir = configDir();
			server = serverKey(mc);
			savedServer = server;
			savedDeep.clear();
			if (configDir != null) {
				savedDeep.addAll(ChannelRecord.deepChannels(configDir, server));
			}
			clearFlags(); // bumps the shared renderer revision without a scanner
		} else {
			flushChannels();
			feed.release();
			resetSession();
		}
	}

	@Override
	public void resetSession() {
		super.resetSession();
		active.clear();
		activeSince.clear();
		tracerBoxes = List.of();
		shown = 0;
		lastRefreshMs = -1L;
		lastSnapshotTick = Long.MIN_VALUE;
		feed.reset();
	}

	@Override
	public void loadConfig(JsonObject json) {
		super.loadConfig(json);
		colorByType.set(ConfigValues.boolValue(json, "color_by_type", false));
		colorBusy.load(json, "color_busy");
	}

	@Override
	public void saveConfig(JsonObject json) {
		super.saveConfig(json);
		json.addProperty("color_by_type", colorByType.value());
		colorBusy.save(json, "color_busy");
	}

	private void flushChannels() {
		if (configDir != null) {
			ChannelRecord.flush(configDir);
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

	private static int kindColor(SoundKinds.Kind kind) {
		return switch (kind) {
			case STORAGE -> 0xFFFFA43A;
			case REDSTONE -> 0xFFFF4B4B;
			case MINING -> 0xFFFFFFFF;
			case BUILDING -> 0xFF5AD66A;
			case MACHINE -> 0xFFFFE15A;
			case EXPLOSION -> 0xFFFF5CFF;
			default -> 0xFFFFFFFF;
		};
	}

	private static void ring(DrawSink sink, double x, double y, double z, double radius, int argb) {
		circle(sink, x, y, z, radius, 0.0, Math.PI * 2.0, 28, argb);
	}

	private static void rotatingRing(DrawSink sink, double x, double y, double z, double radius,
			double timeSec, int argb, int alpha) {
		double start = timeSec * 1.4 % (Math.PI * 2.0);
		circle(sink, x, y, z, radius, start, start + Math.PI * 0.65, 12,
				withAlpha(argb, Math.max(0, Math.min(255, alpha * 3 / 4))));
	}

	private static void circle(DrawSink sink, double x, double y, double z, double radius,
			double start, double end, int segments, int argb) {
		double step = (end - start) / segments;
		double previousX = x + Math.cos(start) * radius;
		double previousZ = z + Math.sin(start) * radius;
		for (int i = 1; i <= segments; i++) {
			double angle = start + step * i;
			double nextX = x + Math.cos(angle) * radius;
			double nextZ = z + Math.sin(angle) * radius;
			sink.line(previousX, y, previousZ, nextX, y, nextZ, 1.4f, argb);
			previousX = nextX;
			previousZ = nextZ;
		}
	}

	private static int withAlpha(int argb, int alpha) {
		int a = Math.max(0, Math.min(255, alpha));
		return (a << 24) | (argb & 0xFFFFFF);
	}

	private static double clamp(double value) {
		return Math.max(0.0, Math.min(1.0, value));
	}
}
