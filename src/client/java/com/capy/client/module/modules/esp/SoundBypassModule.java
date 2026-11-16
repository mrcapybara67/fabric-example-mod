package com.capy.client.module.modules.esp;

import com.capy.client.config.ConfigValues;
import com.capy.client.finder.core.ActivityGrid;
import com.capy.client.finder.core.ChannelRecord;
import com.capy.client.finder.core.ChannelStats;
import com.capy.client.finder.core.Chunks;
import com.capy.client.finder.core.DrawSink;
import com.capy.client.finder.core.FlagStyles;
import com.capy.client.finder.core.SoundBypassFeed;
import com.capy.client.finder.core.SoundKinds;
import com.capy.client.finder.core.TerrainPlate;
import com.capy.client.module.BooleanSetting;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Sound Bypass: marks player or machine activity from packets the server has
 * already sent. It never scans chunks and never sends anything, so it can only
 * mark what the server actually delivers.
 */
public final class SoundBypassModule extends FinderModule {
	private static final double MAX_DISTANCE = 192.0;
	private static final double MIN_SCORE = 6.0;
	private static final int MAX_CELLS = 8;
	private static final int MAX_DRAWN = 6;
	private static final long REFRESH_MS = 500L;
	private static final long SNAPSHOT_TICKS = 5L;

	private final BooleanSetting colorByType = setting(new BooleanSetting("color_by_type", true));
	private final SoundBypassFeed feed = SoundBypassFeed.get();
	private final List<CellView> active = new ArrayList<>();
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
		super("sound_bypass", 0xFFFFB347);
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
		Set<Long> plates = new HashSet<>();
		List<CellView> ordered = new ArrayList<>(active.subList(0, shown));
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			ordered.sort(Comparator.comparingDouble(view -> mc.player.distanceToSqr(
					view.cell().x(), view.cell().y(), view.cell().z())));
		}
		for (CellView view : ordered) {
			ActivityGrid.Cell cell = view.cell();
			double distance = flagDistance(view.chunkKey());
			if (!drawableFlag(distance)) {
				continue;
			}
			double fade = clamp((cell.score() - MIN_SCORE) / MIN_SCORE);
			if (fade <= 0.01) {
				continue;
			}
			int base = colorByType.value() ? kindColor(cell.dominant()) : colorArgb(timeSec);
			int alpha = (int) Math.round(((base >>> 24) & 0xFF) * fade);
			if (alpha <= 1) {
				continue;
			}
			int plateColor = withAlpha(base, Math.min(80, alpha / 3));
			int[] heights = cachedTerrain(view.chunkKey());
			if (heights != null && plates.add(view.chunkKey())) {
				TerrainPlate.emit(sink, cell.sx(), cell.sz(), heights, plateColor, 0.04);
			}
			if (fullStyle(distance, 240)) {
				long period = cell.farm() ? 900L : 1500L;
				FlagStyles.sonar(sink, cell.x(), cell.y() + 0.03, cell.z(), base & 0xFFFFFF,
						4.4, timeMs, period);
				if (heights != null) {
					double surface = TerrainPlate.average(heights);
					if (!Double.isNaN(surface)) {
						FlagStyles.depthGauge(sink, cell.x(), cell.z(), surface + 0.06, cell.y(),
								base & 0xFFFFFF, timeMs);
					}
				}
			}
		}
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
		List<ActivityGrid.Cell> top = feed.top(now, MIN_SCORE, MAX_CELLS);
		List<CellView> next = new ArrayList<>();
		Set<Long> keys = new LinkedHashSet<>();
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
			next.add(new CellView(cell, key, reason(cell)));
			keys.add(key);
		}
		active.clear();
		active.addAll(next);
		shown = Math.min(MAX_DRAWN, active.size());
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
	public String statusLine() {
		ChannelStats stats = feed.stats();
		List<ChannelStats.Channel> observed = new ArrayList<>();
		for (ChannelStats.Channel channel : ChannelStats.Channel.values()) {
			if (stats.total(channel) > 0) {
				observed.add(channel);
			}
		}
		boolean allCulled = !observed.isEmpty();
		for (ChannelStats.Channel channel : observed) {
			if (!stats.silentDeep(channel)) {
				allCulled = false;
				break;
			}
		}
		if (allCulled) {
			return "this server sends no underground activity data";
		}
		StringBuilder line = new StringBuilder(stats.line());
		for (ChannelStats.Channel channel : ChannelStats.Channel.values()) {
			if (stats.silentDeep(channel)) {
				line.append(" | ").append(channel.name().toLowerCase(Locale.ROOT))
						.append(" culled underground?");
			}
		}
		line.append(" | deep: ").append(savedDeep.isEmpty() ? "none saved"
				: String.join(", ", new TreeSet<>(savedDeep)));
		return line.toString();
	}

	@Override
	public String reliability() {
		return "reads sent packets only";
	}

	@Override
	public String info() {
		return Integer.toString(shown);
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
		shown = 0;
		lastRefreshMs = -1L;
		lastSnapshotTick = Long.MIN_VALUE;
		feed.reset();
	}

	@Override
	public void loadConfig(JsonObject json) {
		super.loadConfig(json);
		colorByType.set(ConfigValues.boolValue(json, "color_by_type", true));
	}

	@Override
	public void saveConfig(JsonObject json) {
		super.saveConfig(json);
		json.addProperty("color_by_type", colorByType.value());
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
