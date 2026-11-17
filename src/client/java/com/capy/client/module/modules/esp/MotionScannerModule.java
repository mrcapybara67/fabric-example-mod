package com.capy.client.module.modules.esp;

import com.capy.client.config.ConfigValues;
import com.capy.client.finder.core.ChunkStyles;
import com.capy.client.finder.core.Chunks;
import com.capy.client.finder.core.DrawSink;
import com.capy.client.finder.core.FlagStyles;
import com.capy.client.finder.core.MotionScores;
import com.capy.client.module.ColorSetting;
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
 */
public final class MotionScannerModule extends FinderModule {
	private static final long EVALUATE_INTERVAL_MS = 1000L;
	private static final double MIN_SCORE = 5.0;

	private final ColorSetting colorVeryBusy = setting(new ColorSetting("color_very_busy", 0xFFFF4D4D));
	private final Set<Long> busy = new HashSet<>();
	private final Set<Long> veryBusy = new HashSet<>();
	private long lastEvaluateMs = -1L;

	public MotionScannerModule() {
		super("motion_scanner", 0xFFFF9F43);
	}

	/** The score comes from packets, not a block scan; the flag pass is in the tick. */
	@Override
	public boolean scan(ClientLevel level, LevelChunk chunk, long deadlineNanos) {
		return true;
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!beginTick(mc)) {
			return;
		}
		long now = System.currentTimeMillis();
		if (lastEvaluateMs >= 0 && now - lastEvaluateMs < EVALUATE_INTERVAL_MS) {
			return;
		}
		lastEvaluateMs = now;
		Map<Long, MotionScores.Level> levels = MotionFeed.scores().busy(now, MIN_SCORE);

		clearFlags();
		busy.clear();
		veryBusy.clear();
		for (Map.Entry<Long, MotionScores.Level> entry : levels.entrySet()) {
			long key = entry.getKey();
			int score = (int) Math.round(MotionFeed.scores().score(key, now));
			markFlag(key, "activity score " + score, score);
			if (entry.getValue() == MotionScores.Level.VERY_BUSY) {
				veryBusy.add(key);
			} else {
				busy.add(key);
			}
		}
		retainTerrain(flags.keySet());
		refreshTerrain(flags.keySet());
	}

	@Override
	public void drawFlags(DrawSink sink, double timeSec, double timeMs) {
		if (flags.isEmpty()) {
			return;
		}
		if (!busy.isEmpty()) {
			draw(sink, busy, colorArgb(timeSec), timeSec, timeMs);
		}
		if (!veryBusy.isEmpty()) {
			draw(sink, veryBusy, colorVeryBusy.argbAt(timeSec), timeSec, timeMs);
		}
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
