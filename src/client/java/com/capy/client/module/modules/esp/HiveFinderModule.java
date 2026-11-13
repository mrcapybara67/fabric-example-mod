package com.capy.client.module.modules.esp;

import com.capy.client.config.ConfigValues;
import com.capy.client.finder.core.Chunks;
import com.capy.client.finder.core.HiveFinder;
import com.capy.client.module.SliderSetting;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
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
	private static final long SCAN_BUDGET_NANOS = 1_500_000L;
	private static final long RESCAN_INTERVAL_TICKS = 1200L;
	private static final long EVALUATE_INTERVAL_TICKS = 20L;
	private static final Predicate<BlockState> HIVE = state ->
			state != null && (state.is(Blocks.BEEHIVE) || state.is(Blocks.BEE_NEST));

	private final SliderSetting mergeRadius = setting(new SliderSetting("merge_radius", 4, 1, 8));
	private final HiveFinder hive = new HiveFinder();
	private final ArrayDeque<Long> queue = new ArrayDeque<>();
	private final Set<Long> queued = new HashSet<>();
	private final Map<Long, Long> retry = new HashMap<>();
	private long lastEnqueueTick = -1L;
	private long lastEvaluateTick = -1L;

	public HiveFinderModule() {
		super("hive_finder", 0xFFF2C14E);
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!beginTick(mc)) {
			return;
		}
		long tick = level.getGameTime();
		moveReadyRetries(tick);
		if (lastEnqueueTick < 0L || tick - lastEnqueueTick >= RESCAN_INTERVAL_TICKS) {
			enqueueLoaded();
			lastEnqueueTick = tick;
		}
		long deadline = System.nanoTime() + SCAN_BUDGET_NANOS;
		while (!queue.isEmpty() && System.nanoTime() < deadline) {
			long key = queue.pollFirst();
			queued.remove(key);
			LevelChunk chunk = level.getChunkSource().getChunkNow(Chunks.x(key), Chunks.z(key));
			if (chunk == null) {
				continue;
			}
			int now = (int) tick;
			if (!hive.settled(key, now)) {
				retry.put(key, tick + HiveFinder.SETTLE_TICKS);
				continue;
			}
			hive.setHit(key, scanChunk(chunk));
		}
		if (queue.isEmpty() && retry.isEmpty() && tick - lastEvaluateTick >= EVALUATE_INTERVAL_TICKS) {
			lastEvaluateTick = tick;
			evaluate();
		}
	}

	private void moveReadyRetries(long tick) {
		if (retry.isEmpty()) {
			return;
		}
		var iterator = retry.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<Long, Long> entry = iterator.next();
			if (entry.getValue() <= tick) {
				iterator.remove();
				if (queued.add(entry.getKey())) {
					queue.addLast(entry.getKey());
				}
			}
		}
	}

	private void enqueueLoaded() {
		int radius = Math.min(12, simulationDistance() + 4);
		var player = Minecraft.getInstance().player;
		if (player == null) {
			return;
		}
		int cx = player.blockPosition().getX() >> 4;
		int cz = player.blockPosition().getZ() >> 4;
		for (int dz = -radius; dz <= radius; dz++) {
			for (int dx = -radius; dx <= radius; dx++) {
				int x = cx + dx;
				int z = cz + dz;
				LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
				if (chunk == null) {
					continue;
				}
				long key = Chunks.key(x, z);
				if (queued.add(key)) {
					queue.addLast(key);
				}
			}
		}
	}

	private boolean scanChunk(LevelChunk chunk) {
		int minY = chunk.getMinY();
		int baseX = chunk.getPos().getMinBlockX();
		int baseZ = chunk.getPos().getMinBlockZ();
		LevelChunkSection[] sections = chunk.getSections();
		for (int index = 0; index < sections.length; index++) {
			LevelChunkSection section = sections[index];
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
								return true;
							}
						}
					}
				}
			}
		}
		return false;
	}

	private void evaluate() {
		if (level == null || Minecraft.getInstance().player == null) {
			return;
		}
		hive.retain(this::loaded);
		Set<Long> merged = HiveFinder.merge(new HashSet<>(hive.hits()), mergeRadius.get());
		for (long key : new HashSet<>(flags)) {
			if (!merged.contains(key)) {
				unmarkFlag(key);
			}
		}
		for (long key : merged) {
			if (!flags.contains(key)) {
				markFlag(key, List.of(surfaceBox(key)));
			}
		}
	}

	@Override
	public void resetSession() {
		super.resetSession();
		hive.clear();
		queue.clear();
		queued.clear();
		retry.clear();
		lastEnqueueTick = -1L;
		lastEvaluateTick = -1L;
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
