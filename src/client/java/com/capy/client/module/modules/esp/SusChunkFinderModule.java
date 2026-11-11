package com.capy.client.module.modules.esp;

import com.capy.client.config.ConfigValues;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.ChoiceSetting;
import net.minecraft.client.Minecraft;
import com.capy.client.module.SliderSetting;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

/**
 * Read-only base-finder heuristic for grown amethyst and lit cave pockets.
 * This module sends no packets and never touches the world.
 *
 * <p>The block signal is an assumption, not a certainty: a grown cluster and a
 * honey-full hive are more likely in a chunk that has been ticking, but they
 * are common in ordinary caves too. The light signal is likewise a heat
 * heuristic, not a proof of a hidden base. Treat a flag as a place to inspect.</p>
 */
public final class SusChunkFinderModule extends BaseFinderModule {
	private static final long EVALUATE_EVERY = 20L;
	private static final int SPREAD = 2;
	private final ChoiceSetting method = setting(new ChoiceSetting("method", 2, "blocks", "light_heat", "both"));
	private final SliderSetting sensitivity = setting(new SliderSetting("sensitivity", 2, 1, 20));
	private final BooleanSetting ungrownVeto = setting(new BooleanSetting("use_ungrown_veto", false));
	private final BooleanSetting susDebug = setting(new BooleanSetting("sus_debug", false));
	private final ScanScheduler scheduler = new ScanScheduler();
	private final HeatMap heat = new HeatMap();
	private final Map<Long, Double> raw = new LinkedHashMap<>();
	private final Map<Long, Boolean> grown = new LinkedHashMap<>();
	private long lastQueueTick = Long.MIN_VALUE;
	private long lastEvaluateTick = Long.MIN_VALUE;
	private boolean absentWarned;

	public SusChunkFinderModule() {
		super("sus_chunk_finder", 0xFFB07CFF);
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!isEnabled()) {
			return;
		}
		beginScan(mc);
		if (level == null || mc.player == null) {
			return;
		}
		long tick = level.getGameTime();
		scheduler.retainLoaded(level);
		if (tick - lastQueueTick >= 40L) {
			lastQueueTick = tick;
			clusterBoxes.clear();
			int radius = simulationDistance.get();
			int cx = mc.player.blockPosition().getX() >> 4;
			int cz = mc.player.blockPosition().getZ() >> 4;
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dx = -radius; dx <= radius; dx++) {
					if (level.getChunkSource().getChunkNow(cx + dx, cz + dz) != null) {
						scheduler.offer(HeatMap.key(cx + dx, cz + dz), tick, 8);
					}
				}
			}
		}
		scheduler.drain(level, tick, 3_500_000L, 24, this::scanChunk);
		if (tick - lastEvaluateTick >= EVALUATE_EVERY) {
			lastEvaluateTick = tick;
			evaluate();
		}
	}

	private void scanChunk(LevelChunk chunk) {
		int cx = chunk.getPos().x;
		int cz = chunk.getPos().z;
		double blocks = method.is("light_heat") ? 0.0 : blocks(chunk);
		double light = method.is("blocks") ? 0.0 : light(chunk);
		double score = method.is("both") ? blocks + light : Math.max(blocks, light);
		long key = HeatMap.key(cx, cz);
		heat.replace(cx, cz, raw.getOrDefault(key, 0.0), score, SPREAD);
		raw.put(key, score);
		while (raw.size() > 4096) {
			long eldest = raw.keySet().iterator().next();
			raw.remove(eldest);
			grown.remove(eldest);
		}
		while (grown.size() > 4096) {
			grown.remove(grown.keySet().iterator().next());
		}
		observe(score);
		double threshold = threshold(sensitivity.get());
		if (susDebug.value() && score > 0.0) {
			debug("sus", key, score, threshold);
		}
		if (method.is("light_heat") && calibration().absent() && !absentWarned) {
			absentWarned = true;
			if (Minecraft.getInstance().player != null) {
				Minecraft.getInstance().player.displayClientMessage(
						net.minecraft.network.chat.Component.literal(
								"[Capy] Sus Chunk Finder: this server sends no usable light below Y 1; light flags are disabled here."), false);
			}
		}
	}

	private double blocks(LevelChunk chunk) {
		int baseX = chunk.getPos().getMinBlockX();
		int baseZ = chunk.getPos().getMinBlockZ();
		int minY = chunk.getMinY();
		LevelChunkSection[] sections = chunk.getSections();
		int contacts = 0;
		int hives = 0;
		Set<BlockPos> stone = new HashSet<>();
		List<BlockPos> clusters = new ArrayList<>();
		for (int index = 0; index < sections.length; index++) {
			LevelChunkSection section = sections[index];
			if (section == null || section.hasOnlyAir()) {
				continue;
			}
			int sectionY = minY + (index << 4);
			if (sectionY + 16 < -64 || sectionY > 64) {
				continue;
			}
			for (int y = 0; y < 16; y++) {
				int worldY = sectionY + y;
				if (worldY < -64 || worldY > 64) {
					continue;
				}
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = section.getBlockState(x, y, z);
						if (state == null) {
							continue;
						}
						if (state.is(Blocks.BEEHIVE) || state.is(Blocks.BEE_NEST)) {
							if (state.hasProperty(BeehiveBlock.HONEY_LEVEL)
									&& state.getValue(BeehiveBlock.HONEY_LEVEL) >= 5) {
								hives++;
							}
							continue;
						}
						if (!amethyst(state)) {
							continue;
						}
						BlockPos pos = new BlockPos(baseX + x, worldY, baseZ + z);
						if (clusters.size() < 400) {
							clusters.add(pos);
						}
						for (BlockPos neighbour : new BlockPos[]{pos.north(), pos.south(), pos.east(),
								pos.west(), pos.above(), pos.below()}) {
							BlockState around = level.getBlockState(neighbour);
							if (around.is(Blocks.STONE)) {
								stone.add(neighbour);
							}
						}
					}
				}
			}
		}
		contacts = stone.size();
		boolean grownHere = clusters.size() > 0;
		grown.put(HeatMap.key(chunk.getPos().x, chunk.getPos().z), grownHere);
		if (renderClusters()) {
			for (BlockPos pos : clusters) {
				if (clusterBoxes.size() < 400) {
					clusterBoxes.add(new AABB(pos).inflate(0.02));
				}
			}
		}
		return contacts + Math.max(0, hives - 1) * Math.max(3, sensitivity.get());
	}

	private static boolean amethyst(BlockState state) {
		return state.is(Blocks.AMETHYST_BLOCK) || state.is(Blocks.BUDDING_AMETHYST)
				|| state.is(Blocks.SMALL_AMETHYST_BUD) || state.is(Blocks.MEDIUM_AMETHYST_BUD)
				|| state.is(Blocks.LARGE_AMETHYST_BUD) || state.is(Blocks.AMETHYST_CLUSTER);
	}

	private double light(LevelChunk chunk) {
		int baseX = chunk.getPos().getMinBlockX();
		int baseZ = chunk.getPos().getMinBlockZ();
		int minY = chunk.getMinY();
		LevelChunkSection[] sections = chunk.getSections();
		int count = 0;
		for (int index = 0; index < sections.length; index++) {
			LevelChunkSection section = sections[index];
			if (section == null) {
				continue;
			}
			int sectionY = minY + (index << 4);
			if (sectionY + 16 < -58 || sectionY > 30) {
				continue;
			}
			for (int y = 0; y < 16; y++) {
				int worldY = sectionY + y;
				if (worldY < -58 || worldY > 30) {
					continue;
				}
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						if (!section.getBlockState(x, y, z).isAir()) {
							continue;
						}
						BlockPos pos = new BlockPos(baseX + x, worldY, baseZ + z);
						if (level.getBrightness(LightLayer.BLOCK, pos) != 0) {
							continue;
						}
						if (touchesLight(pos)) {
							count++;
						}
					}
				}
			}
		}
		return count / 12.0;
	}

	private boolean touchesLight(BlockPos pos) {
		for (BlockPos neighbour : new BlockPos[]{pos.north(), pos.south(), pos.east(),
				pos.west(), pos.above(), pos.below()}) {
			if (level.getBrightness(LightLayer.BLOCK, neighbour) >= 4) {
				return true;
			}
		}
		return false;
	}

	private void evaluate() {
		Set<Long> found = new HashSet<>();
		double threshold = threshold(sensitivity.get());
		for (long key : peaks(heat, SPREAD, 3)) {
			double score = raw.getOrDefault(key, 0.0);
			if (score < threshold) {
				continue;
			}
			if (ungrownVeto.value() && Boolean.FALSE.equals(grown.get(key))) {
				continue;
			}
			found.add(key);
		}
		for (long key : new ArrayList<>(flags)) {
			if (!found.contains(key)) {
				unmark(key);
			}
		}
		for (long key : found) {
			mark(key);
		}
	}

	@Override
	protected void clear() {
		super.clear();
		scheduler.clear();
		heat.clear();
		raw.clear();
		grown.clear();
		lastQueueTick = Long.MIN_VALUE;
		lastEvaluateTick = Long.MIN_VALUE;
		absentWarned = false;
	}

	@Override
	public void loadConfig(JsonObject json) {
		super.loadConfig(json);
		if (json != null && json.has("method")) {
			method.select(ConfigValues.stringValue(json, "method", "both"));
		}
		sensitivity.set(ConfigValues.intValue(json, "sensitivity", 2));
		ungrownVeto.set(ConfigValues.boolValue(json, "use_ungrown_veto", false));
		susDebug.set(ConfigValues.boolValue(json, "sus_debug", false));
	}

	@Override
	public void saveConfig(JsonObject json) {
		super.saveConfig(json);
		json.addProperty("method", method.option());
		json.addProperty("sensitivity", sensitivity.get());
		json.addProperty("use_ungrown_veto", ungrownVeto.value());
		json.addProperty("sus_debug", susDebug.value());
	}
}
