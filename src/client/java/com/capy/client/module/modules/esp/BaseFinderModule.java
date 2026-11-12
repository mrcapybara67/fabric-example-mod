package com.capy.client.module.modules.esp;

import com.capy.client.config.ConfigValues;
import com.capy.client.module.ActionSetting;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.Category;
import com.capy.client.module.ChoiceSetting;
import com.capy.client.module.ColorSetting;
import com.capy.client.module.Configurable;
import com.capy.client.module.Module;
import com.capy.client.module.ModuleManager;
import com.capy.client.module.SliderSetting;
import com.capy.client.module.Tickable;
import com.capy.client.module.modules.antiadmin.ServerProfileMatcher;
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
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.AABB;

/**
 * Shared state for the three read-only base finders.
 *
 * <p>None of these modules sends a packet, changes a block or interacts with
 * the world. They only read chunks the server already sent, and every flag is
 * a heuristic rather than a statement of certainty. A local maximum is only an
 * agreement between observations, which is why the combined view is described
 * as the strongest evidence this system can produce, not as proof.</p>
 */
public abstract class BaseFinderModule extends Module implements Tickable, Configurable {
	private static final int MAX_FLAGS = 512;
	private static final int MAX_ANNOUNCED = 512;
	private static final int MAX_CALIBRATIONS = 16;
	private static final long OLD_FLAG_NANOS = 60_000_000_000L;
	private static final long STATUS_INTERVAL_NANOS = 1_000_000_000L;

	protected final ColorSetting color;
	protected final SliderSetting simulationDistance;
	protected final SliderSetting alpha;
	protected final SliderSetting plateY;
	protected final SliderSetting plateThickness;
	protected final BooleanSetting outline;
	protected final BooleanSetting renderClusters;
	protected final BooleanSetting combinedView;
	protected final BooleanSetting ping;
	protected final BooleanSetting toast;
	protected final BooleanSetting disconnectOnFlag;
	protected final BooleanSetting debug;
	protected final ChoiceSetting plateMode;
	protected final BooleanSetting tracer;
	protected final SliderSetting cornerRadius;
	protected final ActionSetting markWrong;

	protected final Set<Long> flags = new LinkedHashSet<>();
	protected final Map<Long, Long> flagTimes = new LinkedHashMap<>();
	protected final Set<Long> announced = new LinkedHashSet<>();
	protected final List<AABB> clusterBoxes = new ArrayList<>();
	protected final Map<String, FinderCalibration> calibrations = new LinkedHashMap<>();
	protected ClientLevel level;
	protected String host = "generic";
	protected int plateVersion;
	protected int debuggedChunks;
	private long statusSecond;
	private long statusStarted;
	private int scannedThisSecond;
	private long scanNanosThisSecond;
	private int totalScanned;
	private double lastThreshold;

	protected BaseFinderModule(String id, int defaultColor) {
		super(Category.BASE_FINDER, id);
		this.color = setting(new ColorSetting("color", defaultColor));
		this.simulationDistance = setting(new SliderSetting("simulation_distance", 8, 2, 16));
		this.alpha = setting(new SliderSetting("alpha", 120, 20, 255));
		this.plateY = setting(new SliderSetting("plate_y", 63, -64, 320));
		this.plateThickness = setting(new SliderSetting("plate_thickness", 1, 1, 10));
		this.plateMode = setting(new ChoiceSetting("plate_mode", 1, "fixed", "surface"));
		this.tracer = setting(new BooleanSetting("tracer", true));
		this.cornerRadius = setting(new SliderSetting("corner_radius", 25, 0, 60, 1));
		this.outline = setting(new BooleanSetting("outline", true));
		this.renderClusters = setting(new BooleanSetting("render_clusters", true));
		this.combinedView = setting(new BooleanSetting("combined_view", true));
		this.ping = setting(new BooleanSetting("sound_ping", true));
		this.toast = setting(new BooleanSetting("toast", false));
		this.disconnectOnFlag = setting(new BooleanSetting("disconnect_on_flag", false));
		this.debug = setting(new BooleanSetting("finder_debug", false));
		this.markWrong = setting(new ActionSetting("mark_wrong", "Mark wrong", this::markWrong));
	}

	public Set<Long> plateChunks() {
		return flags;
	}

	public int plateVersion() {
		return plateVersion;
	}

	/** Changes whenever a value that affects the cached plate colours changes. */
	public int plateStyle() {
		long now = System.nanoTime();
		boolean old = false;
		for (long when : flagTimes.values()) {
			if (now - when > OLD_FLAG_NANOS) {
				old = true;
				break;
			}
		}
		return (color.argb() * 31 + alpha.get() * 17 + (combinedView.value() ? 1 : 0)) * 31
				+ (old ? 1 : 0);
	}

	/** Clears session state on disconnect or dimension change. */
	public void resetSession() {
		clear();
	}

	public int plateColor(long key) {
		int argb = color.argb();
		int a = Math.max(0, Math.min(255, alpha.get()));
		if (combinedView.value() && agreement(key) >= 2) {
			a = Math.min(255, a + 70);
		}
		long when = flagTimes.getOrDefault(key, 0L);
		if (when > 0L && System.nanoTime() - when > OLD_FLAG_NANOS) {
			a = Math.max(20, a / 3);
		}
		return (a << 24) | (argb & 0x00FFFFFF);
	}

	/** How many finder modules currently include this chunk. */
	public static int agreement(long key) {
		int count = 0;
		for (Module module : ModuleManager.get().modules()) {
			if (module instanceof BaseFinderModule finder && finder.isEnabled()
					&& finder.plateChunks().contains(key)) {
				count++;
			}
		}
		return count;
	}

	public int plateY() {
		return plateY.get();
	}

	/** Plate thickness in tenths of a block. */
	public double plateThickness() {
		return plateThickness.get() / 10.0;
	}

	public boolean surfacePlate() {
		return plateMode.is("surface");
	}

	public boolean tracers() {
		return tracer.value();
	}

	/** Corner radius in tenths of a block. */
	public double cornerRadius() {
		return cornerRadius.get() / 10.0;
	}

	public boolean outlines() {
		return outline.value();
	}

	public boolean renderClusters() {
		return renderClusters.value();
	}

	public List<AABB> boxes() {
		return clusterBoxes;
	}

	protected void beginScan(Minecraft mc) {
		if (mc == null || mc.level == null || mc.player == null) {
			clear();
			return;
		}
		if (level != mc.level) {
			clear();
			level = mc.level;
		}
		host = ServerProfileMatcher.hostOf(mc);
		if (host == null || host.isBlank()) {
			host = "generic";
		}
	}

	/** Adds a completed scan to the debug status counters. */
	protected void recordScan(long nanos) {
		long now = System.nanoTime();
		if (statusSecond == 0L) {
			statusSecond = now;
			statusStarted = now;
		}
		if (now - statusStarted >= STATUS_INTERVAL_NANOS) {
			statusSecond = now;
			statusStarted = now;
			scannedThisSecond = 0;
			scanNanosThisSecond = 0L;
		}
		totalScanned++;
		scannedThisSecond++;
		scanNanosThisSecond += Math.max(0L, nanos);
	}

	/** One compact line, at most once a second, only with finder_debug on. */
	protected void status(Minecraft mc, String name, int queueSize, double threshold) {
		lastThreshold = threshold;
		if (!debug.value() || mc == null || mc.player == null) {
			return;
		}
		long now = System.nanoTime();
		if (statusSecond == 0L) {
			statusSecond = now;
		}
		if (now - statusSecond < STATUS_INTERVAL_NANOS) {
			return;
		}
		statusSecond = now;
		String line = String.format(java.util.Locale.ROOT,
				"[Capy] %s scans=%d queue=%d ms/s=%.1f threshold=%.2f flags=%d",
				name, totalScanned, queueSize, scanNanosThisSecond / 1.0e6, lastThreshold, flags.size());
		mc.player.displayClientMessage(Component.literal(line), false);
		scannedThisSecond = 0;
		scanNanosThisSecond = 0L;
	}

	/** Dispatches a chunk-load event to every live finder. */
	public static void chunkLoaded(int chunkX, int chunkZ) {
		for (Module module : ModuleManager.get().modules()) {
			if (module instanceof BaseFinderModule finder && finder.isEnabled()) {
				long key = HeatMap.key(chunkX, chunkZ);
				Guard.run("finder chunk load", () -> finder.onChunkLoaded(key));
			}
		}
	}

	/** Dispatches a block-change event to every live finder. */
	public static void blockChanged(int x, int z) {
		long key = HeatMap.key(x >> 4, z >> 4);
		for (Module module : ModuleManager.get().modules()) {
			if (module instanceof BaseFinderModule finder && finder.isEnabled()) {
				Guard.run("finder block change", () -> finder.onChunkChanged(key));
			}
		}
	}

	protected void onChunkLoaded(long key) {
	}

	protected void onChunkChanged(long key) {
	}

	protected FinderCalibration calibration() {
		FinderCalibration calibration = calibrations.get(host);
		if (calibration == null) {
			calibration = new FinderCalibration();
			calibrations.put(host, calibration);
			while (calibrations.size() > MAX_CALIBRATIONS) {
				calibrations.remove(calibrations.keySet().iterator().next());
			}
		}
		return calibration;
	}

	protected void observe(double raw) {
		calibration().observe(raw);
	}

	protected double threshold(double configured) {
		return calibration().threshold(configured);
	}

	protected void debug(String name, long chunk, double raw, double threshold) {
		if (!debug.value()) {
			return;
		}
		debuggedChunks++;
		if (debuggedChunks % 64 != 0) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.player != null) {
			mc.player.displayClientMessage(Component.literal("[Capy] " + name + " chunk "
					+ HeatMap.x(chunk) + "," + HeatMap.z(chunk) + " raw="
					+ String.format(java.util.Locale.ROOT, "%.2f", raw) + " threshold="
					+ String.format(java.util.Locale.ROOT, "%.2f", threshold)), false);
		}
	}

	protected boolean mark(long key) {
		boolean added = !flags.contains(key);
		if (added) {
			while (flags.size() >= MAX_FLAGS) {
				long eldest = flags.iterator().next();
				flags.remove(eldest);
				flagTimes.remove(eldest);
			}
			flags.add(key);
			plateVersion++;
		}
		flagTimes.putIfAbsent(key, System.nanoTime());
		if (added) {
			announce(key);
		}
		return added;
	}

	protected void unmark(long key) {
		if (flags.remove(key)) {
			plateVersion++;
		}
		flagTimes.remove(key);
	}

	protected void announce(long key) {
		if (!announced.add(key)) {
			return;
		}
		while (announced.size() > MAX_ANNOUNCED) {
			announced.remove(announced.iterator().next());
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.player == null) {
			return;
		}
		String text = "Possible base chunk " + HeatMap.x(key) + ", " + HeatMap.z(key);
		mc.player.displayClientMessage(Component.literal("[Capy] " + text), false);
		if (ping.value() && mc.getSoundManager() != null) {
			mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
					SoundEvents.NOTE_BLOCK_PLING.value(), 1.35f, 0.45f));
		}
		if (toast.value()) {
			// The chat line is the durable record. A toast is intentionally
			// optional because some launchers do not expose the toast manager.
			net.minecraft.client.gui.components.toasts.SystemToast.add(mc.getToastManager(),
					net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
					Component.literal("Capy finder"), Component.literal(text));
		}
		if (disconnectOnFlag.value() && mc.getConnection() != null
				&& mc.getConnection().getConnection() != null) {
			mc.getConnection().getConnection().disconnect(Component.literal(
					"Capy: flagged " + HeatMap.x(key) + ", " + HeatMap.z(key)));
		}
	}

	protected void markWrong() {
		calibration().markWrong();
		calibrations.put(host, calibration());
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.player != null) {
			mc.player.displayClientMessage(Component.literal(
					"[Capy] Threshold for " + host + " raised; new floor="
							+ String.format(java.util.Locale.ROOT, "%.1f", calibration().threshold(1))), false);
		}
	}

	protected List<Long> peaks(HeatMap heatMap, int radius, int minNeighbors) {
		List<Long> out = new ArrayList<>();
		for (long key : heatMap.peaks(radius)) {
			int neighbors = 0;
			for (int dz = -1; dz <= 1; dz++) {
				for (int dx = -1; dx <= 1; dx++) {
					if (heatMap.score(HeatMap.x(key) + dx, HeatMap.z(key) + dz) > 0.0) {
						neighbors++;
					}
				}
			}
			if (neighbors >= minNeighbors) {
				out.add(key);
			}
		}
		return out;
	}

	public boolean debugEnabled() {
		return debug.value();
	}

	@Override
	public void loadConfig(JsonObject json) {
		color.set(ConfigValues.intValue(json, "color", color.get()));
		simulationDistance.set(ConfigValues.intValue(json, "simulation_distance", 8));
		alpha.set(ConfigValues.intValue(json, "alpha", 120));
		plateY.set(ConfigValues.intValue(json, "plate_y", 63));
		plateThickness.set(ConfigValues.intValue(json, "plate_thickness", 1));
		plateMode.select(ConfigValues.stringValue(json, "plate_mode", "surface"));
		tracer.set(ConfigValues.boolValue(json, "tracer", true));
		cornerRadius.set(ConfigValues.intValue(json, "corner_radius", 25));
		outline.set(ConfigValues.boolValue(json, "outline", true));
		renderClusters.set(ConfigValues.boolValue(json, "render_clusters", true));
		combinedView.set(ConfigValues.boolValue(json, "combined_view", true));
		ping.set(ConfigValues.boolValue(json, "sound_ping", true));
		toast.set(ConfigValues.boolValue(json, "toast", false));
		disconnectOnFlag.set(ConfigValues.boolValue(json, "disconnect_on_flag", false));
		debug.set(ConfigValues.boolValue(json, "finder_debug", false));
		calibrations.clear();
		if (json != null && json.has("calibrations") && json.get("calibrations").isJsonObject()) {
			JsonObject stored = json.getAsJsonObject("calibrations");
			for (String key : stored.keySet()) {
				if (stored.get(key).isJsonObject()) {
					calibrations.put(key, FinderCalibration.fromJson(stored.getAsJsonObject(key)));
				}
				if (calibrations.size() >= MAX_CALIBRATIONS) {
					break;
				}
			}
		}
		clear();
	}

	@Override
	public void saveConfig(JsonObject json) {
		json.addProperty("color", color.argb());
		json.addProperty("simulation_distance", simulationDistance.get());
		json.addProperty("alpha", alpha.get());
		json.addProperty("plate_y", plateY.get());
		json.addProperty("plate_thickness", plateThickness.get());
		json.addProperty("plate_mode", plateMode.option());
		json.addProperty("tracer", tracer.value());
		json.addProperty("corner_radius", cornerRadius.get());
		json.addProperty("outline", outline.value());
		json.addProperty("render_clusters", renderClusters.value());
		json.addProperty("combined_view", combinedView.value());
		json.addProperty("sound_ping", ping.value());
		json.addProperty("toast", toast.value());
		json.addProperty("disconnect_on_flag", disconnectOnFlag.value());
		json.addProperty("finder_debug", debug.value());
		JsonObject stored = new JsonObject();
		for (Map.Entry<String, FinderCalibration> entry : calibrations.entrySet()) {
			stored.add(entry.getKey(), entry.getValue().toJson());
		}
		json.add("calibrations", stored);
	}

	@Override
	protected void onToggle(boolean state) {
		if (!state) {
			clear();
		}
	}

	protected void clear() {
		flags.clear();
		flagTimes.clear();
		announced.clear();
		clusterBoxes.clear();
		level = null;
		plateVersion++;
	}
}
