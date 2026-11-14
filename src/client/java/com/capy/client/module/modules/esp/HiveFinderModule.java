package com.capy.client.module.modules.esp;

import com.capy.client.config.ConfigValues;
import com.capy.client.finder.core.ChunkStyles;
import com.capy.client.finder.core.Chunks;
import com.capy.client.finder.core.DrawSink;
import com.capy.client.finder.core.HiveFinder;
import com.capy.client.module.SliderSetting;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Read-only full-beehive finder. It sends no packets and never changes the
 * world.
 *
 * <p>A naturally generated hive fills only when its chunk has been ticking near
 * a player, so a group of full hives is useful evidence of a visited area. The
 * scan waits {@link HiveFinder#SETTLE_TICKS} after a chunk appears, then records
 * a hit only for hives at the maximum honey level. Nearby hits are merged into
 * one middle chunk so one base does not produce a wall of flags.</p>
 */
public final class HiveFinderModule extends FinderModule {
	private static final long EVALUATE_INTERVAL_TICKS = 20L;
	private static final Predicate<BlockState> HIVE = state ->
			state != null && (state.is(Blocks.BEEHIVE) || state.is(Blocks.BEE_NEST));

	private final SliderSetting mergeRadius = setting(new SliderSetting("merge_radius", 4, 1, 8));
	private final HiveFinder hive = new HiveFinder();
	private final Map<Long, Long> retry = new HashMap<>();
	private long lastEvaluateTick = -1L;
	private boolean scanning;
	private long scanKey;
	private int scanSection;

	public HiveFinderModule() {
		super("hive_finder", 0xFFF2C14E);
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!beginTick(mc)) {
			return;
		}
		moveReadyRetries();
		long tick = level.getGameTime();
		if (lastEvaluateTick < 0L || tick - lastEvaluateTick >= EVALUATE_INTERVAL_TICKS) {
			lastEvaluateTick = tick;
			evaluate();
		}
	}

	@Override
	public boolean scan(ClientLevel level, LevelChunk chunk, long deadlineNanos) {
		this.level = level;
		long key = Chunks.key(chunk.getPos().x, chunk.getPos().z);
		long tick = level.getGameTime();
		if (!hive.settled(key, (int) tick)) {
			retry.putIfAbsent(key, tick + HiveFinder.SETTLE_TICKS);
			return true;
		}
		retry.remove(key);

		if (!scanning || scanKey != key) {
			scanning = true;
			scanKey = key;
			scanSection = 0;
		}
		int minY = chunk.getMinY();
		int baseX = chunk.getPos().getMinBlockX();
		int baseZ = chunk.getPos().getMinBlockZ();
		LevelChunkSection[] sections = chunk.getSections();
		for (int index = scanSection; index < sections.length; index++) {
			if (System.nanoTime() >= deadlineNanos) {
				scanSection = index;
				return false;
			}
			LevelChunkSection section = sections[index];
			scanSection = index + 1;
			if (section == null || section.hasOnlyAir() || !section.maybeHas(HIVE)) {
				continue;
			}
			int sectionY = minY + (index << 4);
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = section.getBlockState(x, y, z);
						if (state.is(Blocks.BEEHIVE) || state.is(Blocks.BEE_NEST)) {
							if (state.getValue(BeehiveBlock.HONEY_LEVEL) >= BeehiveBlock.MAX_HONEY_LEVELS) {
								hive.setHit(key, true);
								clearScan();
								return true;
							}
						}
					}
				}
			}
		}
		hive.setHit(key, false);
		clearScan();
		return true;
	}

	private void moveReadyRetries() {
		if (retry.isEmpty() || level == null) {
			return;
		}
		long tick = level.getGameTime();
		var iterator = retry.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<Long, Long> entry = iterator.next();
			if (entry.getValue() <= tick) {
				iterator.remove();
				FinderScanner.get().requeue(entry.getKey());
			}
		}
	}

	private void evaluate() {
		if (level == null || Minecraft.getInstance().player == null) {
			return;
		}
		hive.retain(this::loaded);
		Set<Long> merged = HiveFinder.merge(new HashSet<>(hive.hits()), mergeRadius.get());
		for (long key : new HashSet<>(flags.keySet())) {
			if (!merged.contains(key)) {
				unmarkFlag(key);
			}
		}
		for (long key : merged) {
			if (!flags.containsKey(key)) {
				markFlag(key, "full hive cluster", 1, List.of(surfaceBox(key)));
			}
		}
	}

	@Override
	public void drawFlags(DrawSink sink, double timeSec, double timeMs) {
		int rgb = colorArgb() & 0xFFFFFF;
		for (long key : flags.keySet()) {
			ChunkStyles.marker(sink, Chunks.x(key), Chunks.z(key), surfaceY(key) + 0.04,
					rgb, 1.0, timeMs, true);
		}
	}

	@Override
	protected String statusHint() {
		return retry.isEmpty() ? "no full hives yet"
				: "waiting for " + retry.size() + (retry.size() == 1 ? " chunk" : " chunks") + " to settle";
	}

	@Override
	protected void onChunkUnloaded(long key) {
		retry.remove(key);
		hive.setHit(key, false);
		if (scanning && scanKey == key) {
			clearScan();
		}
	}

	private void clearScan() {
		scanning = false;
		scanKey = 0L;
		scanSection = 0;
	}

	@Override
	public void resetSession() {
		super.resetSession();
		hive.clear();
		retry.clear();
		lastEvaluateTick = -1L;
		clearScan();
	}

	@Override
	public void loadConfig(JsonObject json) {
		super.loadConfig(json);
		mergeRadius.set(ConfigValues.intValue(json, "merge_radius", 4));
	}

	@Override
	public void saveConfig(JsonObject json) {
		super.saveConfig(json);
		json.addProperty("merge_radius", mergeRadius.get());
	}
}
