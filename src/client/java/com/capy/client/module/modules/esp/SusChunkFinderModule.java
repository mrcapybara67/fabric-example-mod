package com.capy.client.module.modules.esp;

import com.capy.client.config.ConfigValues;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.ChoiceSetting;
import com.capy.client.module.SliderSetting;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.phys.AABB;

/**
 * Read-only base-finder heuristic for grown amethyst and lit cave pockets.
 * This module sends no packets and never touches the world.
 *
 * <p>Scanning is event-driven and sliced: a chunk is queued on load, on a
 * block/light change, and once every 60 seconds as a safety net. Each slice
 * handles one 16-block section. The light pass reads one {@link DataLayer} per
 * section instead of asking the level for every air cell.</p>
 */
public final class SusChunkFinderModule extends BaseFinderModule {
	private static final int SPREAD = 2;
	private static final double MIN_THRESHOLD = 1.0;
	private static final long SAFETY_RESCAN_TICKS = 1200L;
	private static final int MAX_STATE = 4096;
	private final ChoiceSetting method = setting(new ChoiceSetting("method", 2, "blocks", "light_heat", "both"));
	private final SliderSetting sensitivity = setting(new SliderSetting("sensitivity", 2, 1, 20));
	private final BooleanSetting ungrownVeto = setting(new BooleanSetting("use_ungrown_veto", false));
	private final BooleanSetting susDebug = setting(new BooleanSetting("sus_debug", false));
	private final ScanScheduler scheduler = new ScanScheduler();
	private final HeatMap heat = new HeatMap();
	private final Map<Long, Double> raw = new LinkedHashMap<>();
	private final Map<Long, Boolean> grown = new LinkedHashMap<>();
	private final Map<Long, List<AABB>> clusterByChunk = new LinkedHashMap<>();
	private final Map<Long, ScanState> scans = new LinkedHashMap<>();
	private long lastSafetyTick = Long.MIN_VALUE;
	private long lastEvaluateTick = Long.MIN_VALUE;
	private boolean absentWarned;

	private static final class ScanState {
		final long started = System.nanoTime();
		int section;
		int contacts;
		int hives;
		int lightCount;
		final LongOpenHashSet stone = new LongOpenHashSet();
		final List<BlockPos> clusters = new ArrayList<>();
	}

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
		boolean first = lastSafetyTick == Long.MIN_VALUE;
		if (first || tick - lastSafetyTick >= SAFETY_RESCAN_TICKS) {
			lastSafetyTick = tick;
			enqueueLoaded(mc, tick);
		}
		scheduler.drain(tick, 2_800_000L, 24, this::scanSlice);
		if (tick - lastEvaluateTick >= 20L) {
			lastEvaluateTick = tick;
			evaluate();
		}
		status(mc, "sus", scheduler.size(), calibration().threshold(MIN_THRESHOLD));
	}

	private void enqueueLoaded(Minecraft mc, long tick) {
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

	@Override
	protected void onChunkLoaded(long key) {
		if (level != null) {
			scheduler.offer(key, level.getGameTime(), 8);
		}
	}

	@Override
	protected void onChunkChanged(long key) {
		if (level != null) {
			scheduler.markDirty(key, level.getGameTime());
		}
	}

	private ScanScheduler.Result scanSlice(long key, int slice) {
		if (level == null) {
			return ScanScheduler.Result.DROP;
		}
		LevelChunk chunk = level.getChunkSource().getChunkNow(HeatMap.x(key), HeatMap.z(key));
		if (chunk == null) {
			return ScanScheduler.Result.DROP;
		}
		ScanState state = scans.get(key);
		if (state == null) {
			state = new ScanState();
			scans.put(key, state);
			while (scans.size() > MAX_STATE) {
				scans.remove(scans.keySet().iterator().next());
			}
		}
		LevelChunkSection[] sections = chunk.getSections();
		if (state.section >= sections.length) {
			finishScan(key, chunk, state);
			scans.remove(key);
			return ScanScheduler.Result.DONE;
		}
		int index = state.section++;
		LevelChunkSection section = sections[index];
		if (section != null && !section.hasOnlyAir()) {
			if (!method.is("light_heat")) {
				scanBlockSection(chunk, section, index, state);
			}
			if (!method.is("blocks")) {
				scanLightSection(chunk, section, index, state);
			}
		}
		if (state.section >= sections.length) {
			finishScan(key, chunk, state);
			scans.remove(key);
			return ScanScheduler.Result.DONE;
		}
		return ScanScheduler.Result.CONTINUE;
	}

	private void finishScan(long key, LevelChunk chunk, ScanState state) {
		int cx = chunk.getPos().x;
		int cz = chunk.getPos().z;
		double blockScore = state.contacts
				+ Math.max(0, state.hives - 1) * Math.max(3, sensitivity.get());
		double lightScore = state.lightCount / 12.0;
		double score = method.is("both") ? blockScore + lightScore
				: method.is("light_heat") ? lightScore : blockScore;
		heat.replace(cx, cz, raw.getOrDefault(key, 0.0), score, SPREAD);
		raw.put(key, score);
		grown.put(key, !state.clusters.isEmpty());
		List<AABB> boxes = new ArrayList<>();
		if (renderClusters()) {
			for (BlockPos pos : state.clusters) {
				if (boxes.size() >= 400) {
					break;
				}
				boxes.add(new AABB(pos).inflate(0.02));
			}
		}
		clusterByChunk.put(key, boxes);
		while (clusterByChunk.size() > MAX_STATE) {
			clusterByChunk.remove(clusterByChunk.keySet().iterator().next());
		}
		while (raw.size() > MAX_STATE) {
			long eldest = raw.keySet().iterator().next();
			raw.remove(eldest);
			grown.remove(eldest);
			clusterByChunk.remove(eldest);
		}
		double threshold = calibration().threshold(MIN_THRESHOLD);
		if (!calibration().isFlagged(score, MIN_THRESHOLD)) {
			calibration().observe(score);
		}
		if (susDebug.value() && score > 0.0) {
			debug("sus", key, score, threshold);
		}
		rebuildClusterBoxes();
		recordScan(System.nanoTime() - state.started);
		if (method.is("light_heat") && calibration().absent() && !absentWarned) {
			absentWarned = true;
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null) {
				mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
						"[Capy] Sus Chunk Finder: this server sends no usable light below Y 1; light flags are disabled here."), false);
			}
		}
	}

	private void scanBlockSection(LevelChunk chunk, LevelChunkSection section, int index, ScanState state) {
		int baseX = chunk.getPos().getMinBlockX();
		int baseZ = chunk.getPos().getMinBlockZ();
		int sectionY = chunk.getMinY() + (index << 4);
		if (sectionY + 16 < -64 || sectionY > 64) {
			return;
		}
		BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
		for (int y = 0; y < 16; y++) {
			int worldY = sectionY + y;
			if (worldY < -64 || worldY > 64) {
				continue;
			}
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					BlockState block = section.getBlockState(x, y, z);
					if (block == null || block.isAir()) {
						continue;
					}
					if (block.is(Blocks.BEEHIVE) || block.is(Blocks.BEE_NEST)) {
						if (block.hasProperty(BeehiveBlock.HONEY_LEVEL)
								&& block.getValue(BeehiveBlock.HONEY_LEVEL) >= 5) {
							state.hives++;
						}
						continue;
					}
					if (!amethyst(block)) {
						continue;
					}
					int wx = baseX + x;
					int wz = baseZ + z;
					if (state.clusters.size() < 400) {
						state.clusters.add(new BlockPos(wx, worldY, wz));
					}
					checkStone(neighbour, state.stone, wx - 1, worldY, wz);
					checkStone(neighbour, state.stone, wx + 1, worldY, wz);
					checkStone(neighbour, state.stone, wx, worldY - 1, wz);
					checkStone(neighbour, state.stone, wx, worldY + 1, wz);
					checkStone(neighbour, state.stone, wx, worldY, wz - 1);
					checkStone(neighbour, state.stone, wx, worldY, wz + 1);
				}
			}
		}
		state.contacts = state.stone.size();
	}

	private void checkStone(BlockPos.MutableBlockPos pos, LongOpenHashSet stone, int x, int y, int z) {
		pos.set(x, y, z);
		if (level.getBlockState(pos).is(Blocks.STONE)) {
			stone.add(pos.asLong());
		}
	}

	private void scanLightSection(LevelChunk chunk, LevelChunkSection section, int index, ScanState state) {
		int sectionY = chunk.getMinY() + (index << 4);
		if (sectionY + 16 < -58 || sectionY > 30) {
			return;
		}
		if (!section.maybeHas(BlockState::isAir)) {
			return;
		}
		LayerLightEventListener lights = level.getLightEngine().getLayerListener(LightLayer.BLOCK);
		DataLayer layer = lights.getDataLayerData(SectionPos.of(chunk.getPos(), sectionY >> 4));
		if (layer == null) {
			layer = new DataLayer(0);
		}
		BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
		int baseX = chunk.getPos().getMinBlockX();
		int baseZ = chunk.getPos().getMinBlockZ();
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
					if (layer.get(x, y, z) != 0) {
						continue;
					}
					if (touchesLight(chunk, layer, lights, probe, baseX + x, worldY, baseZ + z,
							x, y, z)) {
						state.lightCount++;
					}
				}
			}
		}
	}

	private boolean touchesLight(LevelChunk chunk, DataLayer layer, LayerLightEventListener lights,
			BlockPos.MutableBlockPos probe, int wx, int wy, int wz, int lx, int ly, int lz) {
		if (near(layer, lx - 1, ly, lz) || near(layer, lx + 1, ly, lz)
				|| near(layer, lx, ly - 1, lz) || near(layer, lx, ly + 1, lz)
				|| near(layer, lx, ly, lz - 1) || near(layer, lx, ly, lz + 1)) {
			return true;
		}
		probe.set(wx - 1, wy, wz);
		if (lx == 0 && level.getBrightness(LightLayer.BLOCK, probe) >= 4) {
			return true;
		}
		probe.set(wx + 1, wy, wz);
		if (lx == 15 && level.getBrightness(LightLayer.BLOCK, probe) >= 4) {
			return true;
		}
		probe.set(wx, wy, wz - 1);
		if (lz == 0 && level.getBrightness(LightLayer.BLOCK, probe) >= 4) {
			return true;
		}
		probe.set(wx, wy, wz + 1);
		if (lz == 15 && level.getBrightness(LightLayer.BLOCK, probe) >= 4) {
			return true;
		}
		return false;
	}

	private static boolean near(DataLayer layer, int x, int y, int z) {
		return x >= 0 && x < 16 && y >= 0 && y < 16 && z >= 0 && z < 16 && layer.get(x, y, z) >= 4;
	}

	private void rebuildClusterBoxes() {
		clusterBoxes.clear();
		for (List<AABB> boxes : clusterByChunk.values()) {
			for (AABB box : boxes) {
				if (clusterBoxes.size() >= 400) {
					return;
				}
				clusterBoxes.add(box);
			}
		}
	}

	private static boolean amethyst(BlockState state) {
		return state.is(Blocks.AMETHYST_BLOCK) || state.is(Blocks.BUDDING_AMETHYST)
				|| state.is(Blocks.SMALL_AMETHYST_BUD) || state.is(Blocks.MEDIUM_AMETHYST_BUD)
				|| state.is(Blocks.LARGE_AMETHYST_BUD) || state.is(Blocks.AMETHYST_CLUSTER);
	}

	/**
	 * Flags every chunk whose own raw score passes the threshold. A local peak
	 * is only used to keep the strongest source first; a peak with a zero own
	 * score can no longer hide a real source next to it.
	 */
	private void evaluate() {
		double threshold = calibration().threshold(MIN_THRESHOLD);
		Set<Long> found = new java.util.HashSet<>();
		for (Map.Entry<Long, Double> entry : raw.entrySet()) {
			long key = entry.getKey();
			double score = entry.getValue();
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
		scans.clear();
		clusterByChunk.clear();
		lastSafetyTick = Long.MIN_VALUE;
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
