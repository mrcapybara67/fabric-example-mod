package com.capy.client.module.modules.esp;

import com.capy.client.config.ConfigValues;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.ChoiceSetting;
import com.capy.client.module.SliderSetting;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Read-only shaft finder. It sends no packets and never changes blocks.
 *
 * <p>The scanned band defaults to Y 0..32 and is converted with the chunk's real
 * minimum Y, so it also works in the nether and the end. A shaft is a vertical
 * run of air whose bottom and top are solid, whose sides are enclosed and which
 * cannot see the sky. Calm requires four side walls and four air blocks;
 * aggressive requires two side walls and two air blocks. Nothing else differs.
 * Every slice scans one 16-block row of columns; a chunk is queued on load, on
 * a block/light change and every 60 seconds as a safety net.</p>
 */
public final class ChunkFinderBetterModule extends BaseFinderModule {
	private static final long SAFETY_RESCAN_TICKS = 1200L;
	private static final double MIN_THRESHOLD = 1.0;
	private final ChoiceSetting detection = setting(new ChoiceSetting("detection", 0, "calm", "aggressive"));
	private final SliderSetting scanMinY = setting(new SliderSetting("scan_min_y", 0, -128, 384));
	private final SliderSetting scanMaxY = setting(new SliderSetting("scan_max_y", 32, -128, 384));
	private final BooleanSetting shaftDebug = setting(new BooleanSetting("shaft_debug", false));
	private final ScanScheduler scheduler = new ScanScheduler();
	private final HeatMap heat = new HeatMap();
	private final Map<Long, Double> raw = new LinkedHashMap<>();
	private final Map<Long, ScanState> scans = new LinkedHashMap<>();
	private long lastSafetyTick = Long.MIN_VALUE;
	private long lastEvaluateTick = Long.MIN_VALUE;
	private boolean blobWarned;
	private String warnedHost = "";

	private static final class ScanState {
		final long started = System.nanoTime();
		int row;
		int shafts;
		int solid;
		int blob;
	}

	public ChunkFinderBetterModule() {
		super("chunk_finder_better", 0xFF62D6B0);
	}

	public interface ShaftWorld {
		boolean air(int x, int y, int z);

		boolean solid(int x, int y, int z);

		boolean sky(int x, int y, int z);
	}

	/**
	 * Pure, testable shaft rule. The run is checked against the exact band, so a
	 * shaft outside the band is never counted.
	 */
	public static int scanColumn(ShaftWorld world, int x, int z, int minY, int maxY,
			int minHeight, int requiredSides) {
		int shafts = 0;
		int runStart = Integer.MIN_VALUE;
		for (int y = minY; y <= maxY + 1; y++) {
			boolean air = y <= maxY && world.air(x, y, z);
			if (air) {
				if (runStart == Integer.MIN_VALUE) {
					runStart = y;
				}
				continue;
			}
			if (runStart == Integer.MIN_VALUE) {
				continue;
			}
			int end = y - 1;
			int height = end - runStart + 1;
			if (height >= minHeight) {
				int mid = (runStart + end) / 2;
				int walls = (world.solid(x, runStart - 1, z) ? 1 : 0)
						+ (world.solid(x, y, z) ? 1 : 0)
						+ (world.solid(x - 1, mid, z) ? 1 : 0)
						+ (world.solid(x + 1, mid, z) ? 1 : 0)
						+ (world.solid(x, mid, z - 1) ? 1 : 0)
						+ (world.solid(x, mid, z + 1) ? 1 : 0);
				if (walls >= requiredSides + 2 && !world.sky(x, mid, z)) {
					shafts++;
				}
			}
			runStart = Integer.MIN_VALUE;
		}
		return shafts;
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
		status(mc, "shaft", scheduler.size(), calibration().threshold(MIN_THRESHOLD));
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
		ScanState state = scans.computeIfAbsent(key, ignored -> new ScanState());
		if (state.row >= 16) {
			finish(key, chunk, state);
			scans.remove(key);
			return ScanScheduler.Result.DONE;
		}
		int z = state.row++;
		boolean calm = detection.is("calm");
		int minHeight = calm ? 4 : 2;
		int sides = calm ? 4 : 2;
		int minY = Math.max(chunk.getMinY(), Math.min(scanMinY.get(), scanMaxY.get()));
		int maxY = Math.min(chunk.getMaxY(), Math.max(scanMinY.get(), scanMaxY.get()));
		ShaftWorld view = new ChunkView(chunk, minY, maxY);
		int baseX = chunk.getPos().getMinBlockX();
		int baseZ = chunk.getPos().getMinBlockZ() + z;
		for (int x = 0; x < 16; x++) {
			state.shafts += scanColumn(view, baseX + x, baseZ, minY, maxY, minHeight, sides);
		}
		for (int y = minY; y <= maxY; y += 16) {
			// The filler warning is a coarse heuristic and only needs one pass.
		}
		if (state.row >= 16) {
			finish(key, chunk, state);
			scans.remove(key);
			return ScanScheduler.Result.DONE;
		}
		return ScanScheduler.Result.CONTINUE;
	}

	private void finish(long key, LevelChunk chunk, ScanState state) {
		heat.replace(chunk.getPos().x, chunk.getPos().z,
				raw.getOrDefault(key, 0.0), state.shafts, 1);
		raw.put(key, (double) state.shafts);
		while (raw.size() > 4096) {
			raw.remove(raw.keySet().iterator().next());
		}
		double threshold = calibration().threshold(MIN_THRESHOLD);
		if (!calibration().isFlagged(state.shafts, MIN_THRESHOLD)) {
			calibration().observe(state.shafts);
		}
		if (shaftDebug.value() && state.shafts > 0) {
			debug("shaft", key, state.shafts, threshold);
		}
		recordScan(System.nanoTime() - state.started);
	}

	private void evaluate() {
		Set<Long> found = new HashSet<>();
		double threshold = calibration().threshold(MIN_THRESHOLD);
		for (Map.Entry<Long, Double> entry : raw.entrySet()) {
			if (entry.getValue() >= threshold) {
				found.add(entry.getKey());
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

	/** The live adapter around one chunk's sections and the world edge data. */
	private final class ChunkView implements ShaftWorld {
		private final LevelChunk chunk;
		private final int minY;
		private final int maxY;
		private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

		ChunkView(LevelChunk chunk, int minY, int maxY) {
			this.chunk = chunk;
			this.minY = minY;
			this.maxY = maxY;
		}

		@Override
		public boolean air(int x, int y, int z) {
			return inBand(y) && state(x, y, z).isAir();
		}

		@Override
		public boolean solid(int x, int y, int z) {
			BlockState state = inBand(y) ? state(x, y, z) : level.getBlockState(probe.set(x, y, z));
			return state != null && !state.isAir() && !state.is(Blocks.WATER) && !state.is(Blocks.LAVA);
		}

		@Override
		public boolean sky(int x, int y, int z) {
			return level.canSeeSky(probe.set(x, y, z));
		}

		private boolean inBand(int y) {
			return y >= minY && y <= maxY;
		}

		private BlockState state(int x, int y, int z) {
			int localX = x - chunk.getPos().getMinBlockX();
			int localZ = z - chunk.getPos().getMinBlockZ();
			int index = (y - chunk.getMinY()) >> 4;
			if (index < 0 || index >= chunk.getSections().length) {
				return Blocks.AIR.defaultBlockState();
			}
			LevelChunkSection section = chunk.getSections()[index];
			if (section == null) {
				return Blocks.AIR.defaultBlockState();
			}
			return section.getBlockState(localX, (y - chunk.getMinY()) & 15, localZ);
		}
	}

	/** Correct section index for a world Y. */
	public static int sectionIndex(int worldY, int minY) {
		return (worldY - minY) >> 4;
	}

	/** Correct local Y for a world Y. */
	public static int localY(int worldY, int minY) {
		return (worldY - minY) & 15;
	}

	@Override
	protected void clear() {
		super.clear();
		scheduler.clear();
		heat.clear();
		raw.clear();
		scans.clear();
		lastSafetyTick = Long.MIN_VALUE;
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
		scanMinY.set(ConfigValues.intValue(json, "scan_min_y", 0));
		scanMaxY.set(ConfigValues.intValue(json, "scan_max_y", 32));
		shaftDebug.set(ConfigValues.boolValue(json, "shaft_debug", false));
	}

	@Override
	public void saveConfig(JsonObject json) {
		super.saveConfig(json);
		json.addProperty("detection", detection.option());
		json.addProperty("scan_min_y", scanMinY.get());
		json.addProperty("scan_max_y", scanMaxY.get());
		json.addProperty("shaft_debug", shaftDebug.value());
	}
}
