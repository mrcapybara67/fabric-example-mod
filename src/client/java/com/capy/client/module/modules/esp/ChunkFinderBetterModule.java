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
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Read-only shaft finder. It sends no packets and never changes blocks.
 *
 * <p>A shaft is a vertical air run in Y 0..32 that is closed above and below
 * and has solid walls. Calm requires four walls, at least four air blocks and
 * no direct sky; Aggressive requires two walls and at least two air blocks.
 * That difference is the whole strictness control. The rule is deliberately
 * conservative because natural cave columns can satisfy the loose form.</p>
 */
public final class ChunkFinderBetterModule extends BaseFinderModule {
	private final ChoiceSetting detection = setting(new ChoiceSetting("detection", 0, "calm", "aggressive"));
	private final BooleanSetting shaftDebug = setting(new BooleanSetting("shaft_debug", false));
	private final ScanScheduler scheduler = new ScanScheduler();
	private final HeatMap heat = new HeatMap();
	private final java.util.Map<Long, Double> raw = new LinkedHashMap<>();
	private long lastQueueTick = Long.MIN_VALUE;
	private long lastEvaluateTick = Long.MIN_VALUE;
	private boolean blobWarned;
	private String warnedHost = "";

	public ChunkFinderBetterModule() {
		super("chunk_finder_better", 0xFF62D6B0);
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
		if (tick - lastEvaluateTick >= 20L) {
			lastEvaluateTick = tick;
			evaluate();
		}
	}

	private void scanChunk(LevelChunk chunk) {
		int calm = detection.is("calm") ? 1 : 0;
		int minHeight = calm == 1 ? 4 : 2;
		int requiredSides = calm == 1 ? 4 : 2;
		int baseX = chunk.getPos().getMinBlockX();
		int baseZ = chunk.getPos().getMinBlockZ();
		int minY = chunk.getMinY();
		LevelChunkSection[] sections = chunk.getSections();
		int shafts = 0;
		int blob = 0;
		int solid = 0;
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int runStart = -1;
				for (int y = 0; y <= 33; y++) {
					BlockState state = y <= 32 ? block(sections, minY, x, y, z) : Blocks.STONE.defaultBlockState();
					boolean air = state != null && state.isAir();
					if (air && runStart < 0) {
						runStart = y;
					}
					if (!air && runStart >= 0) {
						int end = y - 1;
						int height = end - runStart + 1;
						if (height >= minHeight) {
							int mid = (runStart + end) / 2;
							BlockPos below = new BlockPos(baseX + x, runStart - 1, baseZ + z);
							BlockPos above = new BlockPos(baseX + x, end + 1, baseZ + z);
							int walls = 0;
							if (solid(below)) {
								walls++;
							}
							if (solid(above)) {
								walls++;
							}
							walls += sideWalls(baseX + x, baseZ + z, mid);
							boolean sky = calm == 1 && level.canSeeSky(new BlockPos(baseX + x, mid, baseZ + z));
							if (walls >= requiredSides + 2 && !sky) {
								shafts++;
							}
						}
						runStart = -1;
					}
					if (state != null && !state.isAir()) {
						solid++;
						if (isBlob(state)) {
							blob++;
						}
					}
				}
			}
		}
		long key = HeatMap.key(chunk.getPos().x, chunk.getPos().z);
		heat.replace(chunk.getPos().x, chunk.getPos().z, raw.getOrDefault(key, 0.0), shafts, 1);
		raw.put(key, (double) shafts);
		while (raw.size() > 4096) {
			raw.remove(raw.keySet().iterator().next());
		}
		observe(shafts);
		double threshold = threshold(1.0);
		if (shaftDebug.value() && shafts > 0) {
			debug("shaft", key, shafts, threshold);
		}
		if (solid > 0 && blob / (double) solid > 0.65 && (!blobWarned || !warnedHost.equals(host))) {
			blobWarned = true;
			warnedHost = host;
			if (Minecraft.getInstance().player != null) {
				Minecraft.getInstance().player.displayClientMessage(
						net.minecraft.network.chat.Component.literal(
								"[Capy] Chunk Finder Better: this server's Y 0..32 looks filler-heavy (possible anti-xray); shaft results here may be incomplete."), false);
			}
		}
	}

	private static BlockState block(LevelChunkSection[] sections, int minY, int x, int y, int z) {
		int sectionY = minY + (y >> 4 << 4);
		int index = (sectionY - minY) >> 4;
		if (index < 0 || index >= sections.length || sections[index] == null) {
			return null;
		}
		return sections[index].getBlockState(x, y & 15, z);
	}

	private boolean solid(BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return state != null && !state.isAir();
	}

	private int sideWalls(int x, int z, int y) {
		int walls = 0;
		for (BlockPos side : new BlockPos[]{
				new BlockPos(x - 1, y, z), new BlockPos(x + 1, y, z),
				new BlockPos(x, y, z - 1), new BlockPos(x, y, z + 1)}) {
			BlockState state = level.getBlockState(side);
			if (state != null && !state.isAir() && !state.is(Blocks.WATER) && !state.is(Blocks.LAVA)) {
				walls++;
			}
		}
		return walls;
	}

	private static boolean isBlob(BlockState state) {
		return state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE) || state.is(Blocks.TUFF)
				|| state.is(Blocks.ANDESITE) || state.is(Blocks.DIORITE) || state.is(Blocks.GRANITE);
	}

	private void evaluate() {
		Set<Long> found = new HashSet<>();
		double threshold = threshold(1.0);
		for (long key : peaks(heat, 1, 2)) {
			if (raw.getOrDefault(key, 0.0) >= threshold) {
				found.add(key);
			}
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
		lastQueueTick = Long.MIN_VALUE;
		lastEvaluateTick = Long.MIN_VALUE;
		blobWarned = false;
		warnedHost = "";
	}

	@Override
	public void loadConfig(JsonObject json) {
		super.loadConfig(json);
		if (json != null && json.has("detection")) {
			detection.select(ConfigValues.stringValue(json, "detection", "calm"));
		}
		shaftDebug.set(ConfigValues.boolValue(json, "shaft_debug", false));
	}

	@Override
	public void saveConfig(JsonObject json) {
		super.saveConfig(json);
		json.addProperty("detection", detection.option());
		json.addProperty("shaft_debug", shaftDebug.value());
	}
}
