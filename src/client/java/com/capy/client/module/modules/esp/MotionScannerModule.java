package com.capy.client.module.modules.esp;

import com.capy.client.config.ConfigValues;
import com.capy.client.finder.core.ChunkStyles;
import com.capy.client.finder.core.Chunks;
import com.capy.client.finder.core.DrawSink;
import com.capy.client.finder.core.FinderRules;
import com.capy.client.finder.core.FlagStyles;
import com.capy.client.finder.core.MotionScores;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.ColorSetting;
import com.capy.client.module.IntSetting;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

/**
 * Motion Scanner: colours chunks by how much activity the client has seen
 * recently. The score is fed by {@link MotionFeed} from packets already
 * arriving, so this module sends nothing and reads no world state.
 *
 * <p>Each source switch is applied at the bus, before a weight reaches a
 * score: turning Containers off cannot leak a chest opening through another
 * path. Memory is the decay window, Sensitivity is the score a chunk must
 * reach, and Ignore Near Me drops chunks around the player.</p>
 */
public final class MotionScannerModule extends FinderModule {
	private static final long EVALUATE_INTERVAL_MS = 1000L;

	private final IntSetting sensitivity = describe(setting(new IntSetting("sensitivity", 12, 2, 80, 1)),
			"The activity score a chunk must reach to be marked.");
	private final IntSetting memory = describe(setting(new IntSetting("memory", 60, 10, 600, 1)),
			"How long activity is remembered, in seconds.");
	private final IntSetting ignoreNearMe = describe(setting(new IntSetting("ignore_near_me", 24, 0, 96, 1)),
			"Drop activity closer than this many blocks to you.");
	private final BooleanSetting containers = describe(setting(new BooleanSetting("containers", true)),
			"Count chests, barrels and shulker boxes opening.");
	private final BooleanSetting doors = describe(setting(new BooleanSetting("doors", true)),
			"Count doors, trapdoors and gates changing state.");
	private final BooleanSetting furnaces = describe(setting(new BooleanSetting("furnaces", true)),
			"Count furnaces lighting and going out.");
	private final BooleanSetting building = describe(setting(new BooleanSetting("building", true)),
			"Count other block changes.");
	private final BooleanSetting redstone = describe(setting(new BooleanSetting("redstone", true)),
			"Count pistons, observers, repeaters and other redstone.");
	private final BooleanSetting itemsAndCarts = describe(setting(new BooleanSetting("items_and_carts", true)),
			"Count moving item entities and minecarts.");
	private final BooleanSetting sounds = describe(setting(new BooleanSetting("sounds", true)),
			"Count block sounds the server sends.");
	private final BooleanSetting notifications = describe(setting(new BooleanSetting("notify", false)),
			"Show a toast when a new chunk becomes active.");
	private final IntSetting cooldown = describe(setting(new IntSetting("cooldown", 120, 10, 900, 1),
			() -> notifications.value()), "Seconds between two activity toasts.");
	private final BooleanSetting chatMessage = describe(setting(new BooleanSetting("chat_message", false),
			() -> notifications.value()), "Write one chat line per new active chunk.");
	private final BooleanSetting labels = describe(setting(new BooleanSetting("labels", true)),
			"Show the activity label for each marked chunk.");
	private final BooleanSetting beams = describe(setting(new BooleanSetting("beams", true)),
			"Draw a vertical beam above every active chunk.");
	private final BooleanSetting tracer = describe(setting(new BooleanSetting("tracers", false)),
			"Draw a fading line from the crosshair to every active chunk.");
	private final ColorSetting colorVeryBusy = describe(
			setting(new ColorSetting("color_very_busy", 0xE6FF5078)),
			"The colour for the busiest chunks.");

	private final Set<Long> busy = new HashSet<>();
	private final Set<Long> veryBusy = new HashSet<>();
	private long lastEvaluateMs = -1L;

	public MotionScannerModule() {
		super("motion_scanner", 0xC878C8FF);
	}

	/** The score comes from packets, not a block scan; the flag pass is in the tick. */
	@Override
	public boolean scan(ClientLevel level, LevelChunk chunk, long deadlineNanos) {
		return true;
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
	public void onTick(Minecraft mc) {
		if (!beginTick(mc)) {
			return;
		}
		MotionFeed.configure(containers.value(), doors.value(), furnaces.value(), building.value(),
				redstone.value(), itemsAndCarts.value(), sounds.value());
		long now = System.currentTimeMillis();
		if (lastEvaluateMs >= 0 && now - lastEvaluateMs < EVALUATE_INTERVAL_MS) {
			return;
		}
		lastEvaluateMs = now;
		double halfLife = FinderRules.halfLifeForWindowSeconds(memory.get());
		Map<Long, MotionScores.Level> levels = MotionFeed.scores().busy(now, sensitivity.get(), halfLife);
		Set<Long> before = new HashSet<>(flags.keySet());

		clearFlags();
		busy.clear();
		veryBusy.clear();
		boolean announced = false;
		for (Map.Entry<Long, MotionScores.Level> entry : levels.entrySet()) {
			long key = entry.getKey();
			if (FinderRules.withinBlocks(Chunks.x(key), Chunks.z(key),
					mc.player.getX(), mc.player.getZ(), ignoreNearMe.get())) {
				continue;
			}
			int score = (int) Math.round(MotionFeed.scores().score(key, now, halfLife));
			markFlag(key, "activity score " + score, score, List.of(surfaceBox(key)));
			if (entry.getValue() == MotionScores.Level.VERY_BUSY) {
				veryBusy.add(key);
			} else {
				busy.add(key);
			}
			if (!before.contains(key)) {
				if (chatMessage.value()) {
					notify.chat(mc, "Motion Scanner: activity near " + (Chunks.x(key) * 16 + 8)
							+ ", " + (Chunks.z(key) * 16 + 8));
				}
				if (notifications.value() && !announced
						&& now - lastNotifyMs >= cooldown.get() * 1000L) {
					announced = true;
					lastNotifyMs = now;
					if (notify.toast(mc, "Motion Scanner", "Activity near "
							+ (Chunks.x(key) * 16 + 8) + ", " + (Chunks.z(key) * 16 + 8))) {
						lastNotifyMs = now;
					}
				}
			}
		}
		retainTerrain(flags.keySet());
		refreshTerrain(flags.keySet());
	}

	private long lastNotifyMs = Long.MIN_VALUE / 2;

	@Override
	public void drawFlags(DrawSink sink, double timeSec, double timeMs) {
		drawSharedChunkFlags(sink, timeSec, timeMs);
	}

	@Override
	protected int sharedFlagColor(long key, double timeSec) {
		return veryBusy.contains(key) ? colorVeryBusy.argbAt(timeSec) : colorArgb(timeSec);
	}

	@Override
	protected boolean sharedFlagBeam() {
		return beams.value();
	}

	private void draw(DrawSink sink, Set<Long> chunks, int argb, double timeSec, double timeMs) {
		int rgb = argb & 0xFFFFFF;
		int alpha = (argb >>> 24) & 0xFF;
		int fill = Math.max(28, Math.round(alpha * 0.38f));
		int edge = Math.min(255, Math.max(170, alpha));
		Set<Long> one = new java.util.LinkedHashSet<>(1);
		List<Long> ordered = new ArrayList<>(chunks);
		ordered.sort(Comparator.comparingDouble(this::flagDistance));
		for (long key : ordered) {
			int[] heights = cachedTerrain(key);
			double distance = flagDistance(key);
			if (heights == null || !drawableFlag(distance)) {
				continue;
			}
			double surface = com.capy.client.finder.core.TerrainPlate.average(heights) + 0.05;
			one.clear();
			one.add(key);
			ChunkStyles.fillContour(sink, one, surface, rgb, fill, edge, timeSec);
			if (fullStyle(distance, 220)) {
				FlagStyles.sonar(sink, Chunks.x(key) * 16.0 + 8, surface + 0.03,
						Chunks.z(key) * 16.0 + 8, rgb, 6.0, timeMs, 1600.0);
			}
		}
	}

	@Override
	protected void onToggle(boolean state) {
		super.onToggle(state);
		if (state) {
			MotionFeed.acquire();
		} else {
			MotionFeed.release();
		}
	}

	@Override
	protected void onChunkUnloaded(long key) {
		MotionFeed.forget(key);
		busy.remove(key);
		veryBusy.remove(key);
	}

	@Override
	public void resetSession() {
		super.resetSession();
		busy.clear();
		veryBusy.clear();
		lastEvaluateMs = -1L;
	}

	@Override
	public void loadConfig(JsonObject json) {
		super.loadConfig(json);
		colorVeryBusy.load(json, "color_very_busy");
	}

	@Override
	public void saveConfig(JsonObject json) {
		super.saveConfig(json);
		colorVeryBusy.save(json, "color_very_busy");
	}
}
