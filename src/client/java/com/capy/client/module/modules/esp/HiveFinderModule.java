package com.capy.client.module.modules.esp;

import com.capy.client.config.ConfigValues;
import com.capy.client.finder.core.ChunkStyles;
import com.capy.client.finder.core.Chunks;
import com.capy.client.finder.core.DrawSink;
import com.capy.client.finder.core.FlagStyles;
import com.capy.client.finder.core.HiveFinder;
import com.capy.client.finder.core.TerrainPlate;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.IntSetting;
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

	private final SliderSetting mergeRadius = describe(setting(new SliderSetting("merge_radius", 4, 1, 8)),
			"Chunks this close are folded into one hive flag.");
	private final IntSetting minimumHives = describe(setting(new IntSetting("min_hives", 2, 1, 8, 1)),
			"How many full hives a chunk needs to count as a cluster.");
	private final BooleanSetting notifications = describe(setting(new BooleanSetting("notifications", false)),
			"Show a short toast when a new hive cluster is found.");
	private final BooleanSetting beam = describe(setting(new BooleanSetting("beam", false)),
			"Draw a vertical beam above every hive cluster.");
	private final HiveFinder hive = new HiveFinder();
	private final Map<Long, Long> retry = new HashMap<>();
	private long lastEvaluateTick = -1L;
	private int appliedHives = -1;
	private boolean scanning;
	private long scanKey;
	private int scanSection;
	private int scanFull;

	public HiveFinderModule() {
		super("hive_finder", 0xFFF2C14E);
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!beginTick(mc)) {
			return;
		}
		int minimum = minimumHives.get();
		if (appliedHives != minimum) {
			// The threshold is part of the cached hit set. Drop the old hits and
			// rescan rather than leaving a one-hive flag that the new rule says
			// is not a cluster.
			appliedHives = minimum;
			hive.clearHits();
			clearScan();
			evaluate();
			FinderScanner.get().queueAllLoaded();
		}
		moveReadyRetries();
		long tick = level.getGameTime();
		if (lastEvaluateTick < 0L || tick - lastEvaluateTick >= EVALUATE_INTERVAL_TICKS) {
			lastEvaluateTick = tick;
			evaluate();
			refreshTerrain(flags.keySet());
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
			scanFull = 0;
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
						if (state != null && (state.is(Blocks.BEEHIVE) || state.is(Blocks.BEE_NEST))) {
							if (state.getValue(BeehiveBlock.HONEY_LEVEL) >= BeehiveBlock.MAX_HONEY_LEVELS) {
								scanFull++;
							}
						}
					}
				}
			}
		}
		hive.setHit(key, scanFull >= minimumHives.get());
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
		boolean announced = false;
		for (long key : merged) {
			if (!flags.containsKey(key)) {
				markFlag(key, "full hive cluster", 1, List.of(surfaceBox(key)));
				if (!announced && notifications.value()) {
					announced = true;
					notify.toast(Minecraft.getInstance(), "Hive finder",
							"Full hive cluster near " + (Chunks.x(key) * 16 + 8) + ", "
									+ (Chunks.z(key) * 16 + 8));
				}
			}
		}
		retainTerrain(merged);
		refreshTerrain(merged);
	}

	@Override
	public void drawFlags(DrawSink sink, double timeSec, double timeMs) {
		drawSharedChunkFlags(sink, timeSec, timeMs);
	}

	@Override
	protected boolean sharedFlagBeam() {
		return beam.value();
	}

	@Override
	protected void onChunkUnloaded(long key) {
		retry.remove(key);
		hive.setHit(key, false);
		dropTerrain(key);
		if (scanning && scanKey == key) {
			clearScan();
		}
	}

	private void clearScan() {
		scanning = false;
		scanKey = 0L;
		scanSection = 0;
		scanFull = 0;
	}

	@Override
	public void resetSession() {
		super.resetSession();
		hive.clear();
		retry.clear();
		terrain.clear();
		lastEvaluateTick = -1L;
		appliedHives = -1;
		clearScan();
	}

	@Override
	public void loadConfig(JsonObject json) {
		super.loadConfig(json);
		mergeRadius.set(ConfigValues.intValue(json, "merge_radius", 4));
		minimumHives.set(ConfigValues.intValue(json, "min_hives", 2));
		appliedHives = -1;
	}

	@Override
	public void saveConfig(JsonObject json) {
		super.saveConfig(json);
		json.addProperty("merge_radius", mergeRadius.get());
		json.addProperty("min_hives", minimumHives.get());
	}
}
