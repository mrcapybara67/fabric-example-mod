package com.capy.client.module.modules.esp;

import com.capy.client.finder.core.ChunkStyles;
import com.capy.client.finder.core.Chunks;
import com.capy.client.finder.core.DrawSink;
import com.capy.client.finder.core.FinderRules;
import com.capy.client.finder.core.FlagStyles;
import com.capy.client.finder.core.GrowthRules;
import com.capy.client.finder.core.TerrainPlate;
import com.capy.client.finder.core.PocketFilter;
import com.capy.client.finder.core.SusChunkEngine;
import com.capy.client.mixin.ClientCommonPacketListenerAccessor;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.IntSetting;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.MangrovePropaguleBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.TwistingVinesBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WeepingVinesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Read-only stalled-growth finder. It sends no packets and never changes the
 * world.
 *
 * <p>A chunk that still holds immature plants which could grow right now is
 * evidence that its random ticks have stopped. The {@link SusChunkEngine}
 * eliminates every chunk within the server's simulation distance of such a
 * record and flags the remaining gaps that three sides of evidence support.
 * The shared scanner feeds this finder one chunk at a time and this consumer
 * saves its section index when the tick budget runs out.</p>
 *
 * <p>Snow and ice count only under strong block light, which is the condition
 * vanilla's melt tick uses. Daylight is sky light and never melts them, so
 * reading the raw brightness would turn every sunlit snowfield into evidence;
 * only a lamp that should already have melted the block does. Everything else
 * is evaluated type-first, so a section full of leaves or crops does not read
 * thousands of neighbours.</p>
 */
public final class StalledGrowthFinderModule extends FinderModule {
	private static final long EVALUATE_INTERVAL_TICKS = 20L;
	private static final int MAX_Y = 3;
	private static final Predicate<BlockState> GROWTH = state -> {
		if (state == null) {
			return false;
		}
		return state.is(Blocks.KELP) || state.is(Blocks.WEEPING_VINES) || state.is(Blocks.TWISTING_VINES)
				|| state.is(Blocks.CAVE_VINES) || state.is(Blocks.BAMBOO) || state.is(Blocks.SUGAR_CANE)
				|| state.is(Blocks.CACTUS) || state.is(Blocks.COCOA) || state.is(Blocks.NETHER_WART)
				|| state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.MELON_STEM)
				|| state.is(Blocks.PUMPKIN_STEM) || state.is(Blocks.VINE)
				|| state.is(Blocks.MANGROVE_PROPAGULE)
				|| state.is(Blocks.BUDDING_AMETHYST) || state.is(Blocks.SMALL_AMETHYST_BUD)
				|| state.is(Blocks.MEDIUM_AMETHYST_BUD) || state.is(Blocks.LARGE_AMETHYST_BUD)
				|| state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT)
				|| state.is(Blocks.OPEN_EYEBLOSSOM) || state.is(Blocks.CLOSED_EYEBLOSSOM)
				|| state.is(Blocks.SNOW) || state.is(Blocks.ICE) || state.is(Blocks.FROSTED_ICE)
				|| state.getBlock() instanceof CropBlock || state.getBlock() instanceof LeavesBlock;
	};

	private final IntSetting simulationDistance = describe(
			setting(new IntSetting("simulation_distance", 4, 2, 16, 1)),
			"The simulation radius used to eliminate chunks; this replaces the server value.");
	private final IntSetting fillIntensity = describe(setting(new IntSetting("fill_intensity", 40, 5, 100, 5)),
			"How strong the chunk plate fill is, in percent of the colour's alpha.");
	private final BooleanSetting followMyHeight = describe(setting(new BooleanSetting("follow_my_height", false)),
			"Ride the plate at your feet instead of hugging the terrain.");
	private final IntSetting height = describe(setting(new IntSetting("height", 63, -64, 320, 1),
			() -> !followMyHeight.value()), "The ceiling for the terrain-hugging plate.");
	private final IntSetting sensitivity = describe(setting(new IntSetting("sensitivity", 40, 1, 500, 1)),
			"How much stalled-growth support a chunk needs to be flagged.");
	private final BooleanSetting holeFilter = describe(setting(new BooleanSetting("hole_filter", false)),
			"Drop candidates close to a long vertical shaft.");
	private final IntSetting holeDistance = describe(setting(new IntSetting("hole_distance", 2, 0, 8, 1),
			() -> holeFilter.value()), "How many chunks around a shaft are dropped.");
	private final BooleanSetting customFlags = describe(setting(new BooleanSetting("custom_flags", false)),
			"Choose which kinds of evidence count instead of accepting all of them.");
	private final BooleanSetting kelp = evidence("kelp", "Kelp columns");
	private final BooleanSetting caveVines = evidence("cave_vines", "Cave vines");
	private final BooleanSetting weepingVines = evidence("weeping_vines", "Weeping and twisting vines");
	private final BooleanSetting bamboo = evidence("bamboo", "Bamboo");
	private final BooleanSetting sugarCane = evidence("sugar_cane", "Sugar cane");
	private final BooleanSetting cactus = evidence("cactus", "Cactus");
	private final BooleanSetting cocoa = evidence("cocoa", "Cocoa");
	private final BooleanSetting berries = evidence("berries", "Sweet berry bushes");
	private final BooleanSetting netherWart = evidence("nether_wart", "Nether wart");
	private final BooleanSetting crops = evidence("crops", "Crops");
	private final BooleanSetting stems = evidence("stems", "Melon and pumpkin stems");
	private final BooleanSetting amethyst = evidence("amethyst", "Amethyst buds and budding amethyst");
	private final BooleanSetting vines = evidence("vines", "Vines");
	private final BooleanSetting leaves = evidence("leaves", "Leaves");
	private final BooleanSetting soil = evidence("soil", "Grass and dirt");
	private final BooleanSetting propagules = evidence("propagules", "Mangrove propagules");
	private final BooleanSetting eyeblossoms = evidence("eyeblossoms", "Eyeblossoms");
	private final BooleanSetting snow = evidence("snow", "Snow layers");
	private final BooleanSetting ice = evidence("ice", "Ice");
	private int appliedCustom = -1;

	private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
	private final Set<Long> renderChunks = new HashSet<>();
	private final Set<Long> oneChunk = new HashSet<>(1);
	private SusChunkEngine engine;
	private long lastEvaluateTick = -1L;
	private boolean scanning;
	private long scanKey;
	private int scanSection;
	private int scanOwed;
	private int scanBlossomRight;
	private int scanBlossomWrong;
	private boolean folia;

	public StalledGrowthFinderModule() {
		super("stalled_growth_finder", 0xFFFF5A7A);
	}

	private BooleanSetting evidence(String key, String tip) {
		return describe(setting(new BooleanSetting(key, true), () -> customFlags.value()), tip);
	}

	/** True when this kind of evidence is currently counted. */
	private boolean allowed(BooleanSetting kind) {
		return !customFlags.value() || kind.value();
	}

	private int customSignature() {
		int hash = customFlags.value() ? 1 : 0;
		BooleanSetting[] kinds = {kelp, caveVines, weepingVines, bamboo, sugarCane, cactus, cocoa,
				berries, netherWart, crops, stems, amethyst, vines, leaves, soil, propagules,
				eyeblossoms, snow, ice};
		for (BooleanSetting kind : kinds) {
			hash = hash * 31 + (kind.value() ? 1 : 0);
		}
		return hash;
	}

	@Override
	public String reliability() {
		int server = level == null ? 0 : level.getServerSimulationDistance();
		if (server > 0 && server != simulationDistance.get()) {
			return "hard to hide, sim " + simulationDistance.get() + " (server " + server + ")";
		}
		return "hard to hide";
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!beginTick(mc)) {
			return;
		}
		if (ensureEngine()) {
			// A new elimination radius invalidates every cached result. Queue
			// the loaded chunks again instead of waiting for them to reload.
			FinderScanner.get().queueAllLoaded();
		}
		int custom = customSignature();
		if (custom != appliedCustom) {
			// Which evidence counts is part of the cached result: a different
			// set makes every old support count a claim about another rule.
			appliedCustom = custom;
			if (engine != null) {
				engine.clear();
			}
			clearFlags();
			renderChunks.clear();
			FinderScanner.get().queueAllLoaded();
		}
		long tick = level.getGameTime();
		if (lastEvaluateTick < 0L || tick - lastEvaluateTick >= EVALUATE_INTERVAL_TICKS) {
			lastEvaluateTick = tick;
			evaluate();
			refreshTerrain(renderChunks);
		}
	}

	@Override
	public boolean scan(ClientLevel level, LevelChunk chunk, long deadlineNanos) {
		this.level = level;
		ensureEngine();
		long key = Chunks.key(chunk.getPos().x, chunk.getPos().z);
		if (!scanning || scanKey != key) {
			scanning = true;
			scanKey = key;
			scanSection = 0;
			scanOwed = 0;
			scanBlossomRight = 0;
			scanBlossomWrong = 0;
		}

		Boolean blossomOpen = eyeblossomsJudgeable() && allowed(eyeblossoms)
				? GrowthRules.eyeblossomsShouldBeOpen(level.getDayTime())
				: null;
		int minY = chunk.getMinY();
		LevelChunkSection[] sections = chunk.getSections();
		int baseX = chunk.getPos().getMinBlockX();
		int baseZ = chunk.getPos().getMinBlockZ();
		for (int index = scanSection; index < sections.length; index++) {
			if (System.nanoTime() >= deadlineNanos) {
				scanSection = index;
				return false;
			}
			LevelChunkSection section = sections[index];
			scanSection = index + 1;
			if (section == null || section.hasOnlyAir() || !section.maybeHas(GROWTH)) {
				continue;
			}
			int sectionY = minY + (index << 4);
			for (int y = 0; y < 16; y++) {
				int worldY = sectionY + y;
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = section.getBlockState(x, y, z);
						if (state == null) {
							continue;
						}
						if (state.is(Blocks.OPEN_EYEBLOSSOM) || state.is(Blocks.CLOSED_EYEBLOSSOM)) {
							if (blossomOpen != null) {
								if (state.is(Blocks.OPEN_EYEBLOSSOM) == blossomOpen.booleanValue()) {
									scanBlossomRight++;
								} else {
									scanBlossomWrong++;
								}
							}
							continue;
						}
						if (isOwed(state, baseX + x, worldY, baseZ + z)) {
							scanOwed++;
						}
					}
				}
			}
		}

		int blossomWeight = GrowthRules.eyeblossomWeight(scanBlossomWrong, scanBlossomRight);
		if (blossomWeight > 0) {
			scanOwed += blossomWeight;
		}
		engine.report(key, scanOwed);
		clearScan();
		return true;
	}

	/** Returns true when the elimination radius changed and a rescan is needed. */
	private boolean ensureEngine() {
		if (level == null) {
			return false;
		}
		updateServerFlavor(Minecraft.getInstance());
		int simulation = evidenceRadius();
		if (engine == null || engine.radius() != simulation) {
			engine = new SusChunkEngine(simulation);
			// A different elimination radius makes the old flags invalid.
			clearFlags();
			renderChunks.clear();
			lastEvaluateTick = -1L;
			clearScan();
			return true;
		}
		return false;
	}

	private int evidenceRadius() {
		// The card's value is the whole answer: default 4 means "use 4", not
		// "use the server's value until I touch this row".
		return simulationDistance.get();
	}

	private void updateServerFlavor(Minecraft mc) {
		boolean next = false;
		if (mc != null && mc.getConnection() instanceof ClientCommonPacketListenerAccessor accessor) {
			String brand = accessor.capy$serverBrand();
			next = brand != null && brand.toLowerCase(Locale.ROOT).contains("folia");
		}
		if (next == folia) {
			return;
		}
		folia = next;
		if (engine != null) {
			// The brand often arrives after the first chunks. Evidence gathered
			// under the wrong rules would outlive the correction, so throw it
			// away and ask the scanner for the loaded chunks again.
			engine.clear();
			clearFlags();
			renderChunks.clear();
			lastEvaluateTick = -1L;
			clearScan();
			FinderScanner.get().queueAllLoaded();
		}
	}

	private boolean isOwed(BlockState state, int x, int y, int z) {
		if (!evidenceEnabled(state)) {
			return false;
		}
		if (state.is(Blocks.KELP)) {
			BlockState above = level.getBlockState(probe.set(x, y + 1, z));
			if (!above.getFluidState().is(FluidTags.WATER)) {
				return false;
			}
			return folia
					? GrowthRules.kelpFolia(true, kelpColumn(x, y, z))
					: GrowthRules.kelp(state.getValue(GrowingPlantHeadBlock.AGE), true);
		}
		if (state.getBlock() instanceof WeepingVinesBlock || state.getBlock() instanceof TwistingVinesBlock) {
			BlockState below = level.getBlockState(probe.set(x, y - 1, z));
			return GrowthRules.hangingVine(state.getValue(GrowingPlantHeadBlock.AGE), below.isAir());
		}
		if (state.is(Blocks.CAVE_VINES)) {
			if (folia) {
				return false;
			}
			BlockState below = level.getBlockState(probe.set(x, y - 1, z));
			return GrowthRules.hangingVine(state.getValue(GrowingPlantHeadBlock.AGE), below.isAir());
		}
		if (state.is(Blocks.BAMBOO)) {
			BlockState above = level.getBlockState(probe.set(x, y + 1, z));
			int stack = 0;
			for (int d = 1; d < 16; d++) {
				if (!level.getBlockState(probe.set(x, y - d, z)).is(Blocks.BAMBOO)) {
					break;
				}
				stack++;
			}
			return GrowthRules.bamboo(state.getValue(BambooStalkBlock.STAGE), above.isAir(), stack,
					level.getMaxLocalRawBrightness(probe.set(x, y + 1, z)));
		}
		if (state.is(Blocks.SUGAR_CANE)) {
			BlockState above = level.getBlockState(probe.set(x, y + 1, z));
			return GrowthRules.sugarCane(above.isAir(), caneHeight(x, y, z));
		}
		if (state.is(Blocks.CACTUS)) {
			BlockState above = level.getBlockState(probe.set(x, y + 1, z));
			return GrowthRules.cactus(above.isAir(), state.getValue(CactusBlock.AGE), cactusHeight(x, y, z));
		}
		if (state.is(Blocks.COCOA)) {
			return GrowthRules.cocoa(state.getValue(CocoaBlock.AGE));
		}
		if (state.is(Blocks.NETHER_WART)) {
			return GrowthRules.netherWart(state.getValue(NetherWartBlock.AGE));
		}
		if (state.is(Blocks.SWEET_BERRY_BUSH)) {
			return GrowthRules.sweetBerry(state.getValue(SweetBerryBushBlock.AGE),
					level.getMaxLocalRawBrightness(probe.set(x, y + 1, z)));
		}
		if (state.is(Blocks.MELON_STEM) || state.is(Blocks.PUMPKIN_STEM)) {
			return GrowthRules.stem(state.getValue(StemBlock.AGE),
					level.getMaxLocalRawBrightness(probe.set(x, y, z)));
		}
		if (state.getBlock() instanceof CropBlock crop) {
			return GrowthRules.crop(crop.isMaxAge(state), level.getMaxLocalRawBrightness(probe.set(x, y, z)));
		}
		if (state.is(Blocks.VINE)) {
			boolean attached = state.getValue(VineBlock.NORTH) || state.getValue(VineBlock.EAST)
					|| state.getValue(VineBlock.SOUTH) || state.getValue(VineBlock.WEST) || state.getValue(VineBlock.UP);
			BlockState below = level.getBlockState(probe.set(x, y - 1, z));
			return GrowthRules.vine(below.isAir(), attached);
		}
		if (state.getBlock() instanceof LeavesBlock) {
			return GrowthRules.leaves(state.getValue(LeavesBlock.DISTANCE), state.getValue(LeavesBlock.PERSISTENT));
		}
		if (state.is(Blocks.MANGROVE_PROPAGULE)) {
			return GrowthRules.propagule(state.getValue(MangrovePropaguleBlock.HANGING),
					state.getValue(MangrovePropaguleBlock.AGE));
		}
		if (state.is(Blocks.GRASS_BLOCK)) {
			BlockState above = level.getBlockState(probe.set(x, y + 1, z));
			return GrowthRules.grassSmothered(above.getLightBlock() >= 15);
		}
		if (state.is(Blocks.DIRT)) {
			BlockState above = level.getBlockState(probe.set(x, y + 1, z));
			boolean smothered = above.getLightBlock() >= 15;
			boolean underWater = above.getFluidState().is(FluidTags.WATER);
			return GrowthRules.dirtUnspread(smothered, underWater, litGrassNearby(x, y, z));
		}
		if (state.is(Blocks.BUDDING_AMETHYST)) {
			if (folia) {
				return false;
			}
			boolean air = false;
			boolean fullWater = false;
			for (Direction direction : Direction.values()) {
				BlockState neighbour = level.getBlockState(probe.set(x, y, z).relative(direction));
				if (neighbour.isAir()) {
					air = true;
				} else if (neighbour.getFluidState().is(FluidTags.WATER)
						&& neighbour.getFluidState().getAmount() >= 8) {
					fullWater = true;
				}
				if (air || fullWater) {
					break;
				}
			}
			return GrowthRules.buddingAmethyst(air, fullWater);
		}
		if (isAmethystBud(state)) {
			Direction facing = state.getValue(AmethystClusterBlock.FACING);
			BlockState support = level.getBlockState(probe.set(x, y, z).relative(facing.getOpposite()));
			return GrowthRules.amethystBud(support.is(Blocks.BUDDING_AMETHYST), false);
		}
		if (state.is(Blocks.SNOW)) {
			return GrowthRules.snow(level.getBrightness(LightLayer.BLOCK, probe.set(x, y, z)),
					state.getValue(SnowLayerBlock.LAYERS));
		}
		if (state.is(Blocks.ICE) || state.is(Blocks.FROSTED_ICE)) {
			return GrowthRules.ice(level.getBrightness(LightLayer.BLOCK, probe.set(x, y, z)));
		}
		return false;
	}

	/** The Custom Flags filter, applied before the expensive per-block checks. */
	private boolean evidenceEnabled(BlockState state) {
		if (!customFlags.value()) {
			return true;
		}
		if (state.is(Blocks.KELP)) {
			return kelp.value();
		}
		if (state.getBlock() instanceof WeepingVinesBlock || state.getBlock() instanceof TwistingVinesBlock) {
			return weepingVines.value();
		}
		if (state.is(Blocks.CAVE_VINES)) {
			return caveVines.value();
		}
		if (state.is(Blocks.BAMBOO)) {
			return bamboo.value();
		}
		if (state.is(Blocks.SUGAR_CANE)) {
			return sugarCane.value();
		}
		if (state.is(Blocks.CACTUS)) {
			return cactus.value();
		}
		if (state.is(Blocks.COCOA)) {
			return cocoa.value();
		}
		if (state.is(Blocks.SWEET_BERRY_BUSH)) {
			return berries.value();
		}
		if (state.is(Blocks.NETHER_WART)) {
			return netherWart.value();
		}
		if (state.is(Blocks.MELON_STEM) || state.is(Blocks.PUMPKIN_STEM)) {
			return stems.value();
		}
		if (state.getBlock() instanceof CropBlock) {
			return crops.value();
		}
		if (state.is(Blocks.VINE)) {
			return vines.value();
		}
		if (state.getBlock() instanceof LeavesBlock) {
			return leaves.value();
		}
		if (state.is(Blocks.MANGROVE_PROPAGULE)) {
			return propagules.value();
		}
		if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT)) {
			return soil.value();
		}
		if (state.is(Blocks.BUDDING_AMETHYST) || isAmethystBud(state)) {
			return amethyst.value();
		}
		if (state.is(Blocks.OPEN_EYEBLOSSOM) || state.is(Blocks.CLOSED_EYEBLOSSOM)) {
			return eyeblossoms.value();
		}
		if (state.is(Blocks.SNOW)) {
			return snow.value();
		}
		if (state.is(Blocks.ICE) || state.is(Blocks.FROSTED_ICE)) {
			return ice.value();
		}
		return true;
	}

	private boolean eyeblossomsJudgeable() {
		return level != null && level.dimension() == Level.OVERWORLD && !level.dimensionType().hasFixedTime();
	}

	private static boolean isAmethystBud(BlockState state) {
		return state.is(Blocks.SMALL_AMETHYST_BUD) || state.is(Blocks.MEDIUM_AMETHYST_BUD)
				|| state.is(Blocks.LARGE_AMETHYST_BUD);
	}

	private int kelpColumn(int x, int y, int z) {
		int column = 1;
		for (int d = 1; d < 3; d++) {
			if (!level.getBlockState(probe.set(x, y - d, z)).is(Blocks.KELP)) {
				break;
			}
			column++;
		}
		return column;
	}

	private int caneHeight(int x, int y, int z) {
		int height = 1;
		for (int d = 1; d < 3; d++) {
			if (!level.getBlockState(probe.set(x, y - d, z)).is(Blocks.SUGAR_CANE)) {
				break;
			}
			height++;
		}
		return height;
	}

	private int cactusHeight(int x, int y, int z) {
		int height = 1;
		for (int d = 1; d < 3; d++) {
			if (!level.getBlockState(probe.set(x, y - d, z)).is(Blocks.CACTUS)) {
				break;
			}
			height++;
		}
		return height;
	}

	/**
	 * Vanilla grass spreads to dirt within one block horizontally and from one
	 * below the dirt up to three above it. The light is read at the grass's top
	 * face, which is the position vanilla checks.
	 */
	private boolean litGrassNearby(int x, int y, int z) {
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= MAX_Y; dy++) {
					int gx = x + dx;
					int gy = y + dy;
					int gz = z + dz;
					if (!level.getBlockState(probe.set(gx, gy, gz)).is(Blocks.GRASS_BLOCK)) {
						continue;
					}
					if (level.getMaxLocalRawBrightness(probe.set(gx, gy + 1, gz)) >= 9) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private void evaluate() {
		Minecraft mc = Minecraft.getInstance();
		if (engine == null || level == null || mc.player == null) {
			return;
		}
		int cx = mc.player.blockPosition().getX() >> 4;
		int cz = mc.player.blockPosition().getZ() >> 4;
		engine.prune(cx, cz, Math.min(64, engine.reach() + 8));
		Set<Long> found = engine.flagged(this::loaded, sensitivity.get());
		if (holeFilter.value()) {
			int distance = holeDistance.get();
			found.removeIf(key -> hasVerticalShaftNear(key, distance));
		}
		List<PocketFilter.Patch> patches = PocketFilter.patches(found, PocketFilter.maxSizeFor(engine.radius()));

		beginBulk();
		clearFlags();
		renderChunks.clear();
		Set<Long> next = new HashSet<>();
		for (PocketFilter.Patch patch : patches) {
			long center = patch.center();
			int support = engine.supportAt(center);
			String reason = "possible player area, evidence " + support + ", "
					+ patch.size() + " chunk(s)";
			markFlag(center, reason, support, List.of(surfaceBox(center)));
			next.addAll(patch.members());
		}
		endBulk();
		renderChunks.addAll(next);
		retainTerrain(renderChunks);
		refreshTerrain(renderChunks);
	}

	@Override
	public void drawFlags(DrawSink sink, double timeSec, double timeMs) {
		if (level == null) {
			return;
		}
		if (eliminationMapVisible() && engine != null) {
			for (long key : engine.clearedChunks()) {
				int[] heights = cachedTerrain(key);
				if (heights != null) {
					TerrainPlate.emit(sink, Chunks.x(key), Chunks.z(key), heights,
							ChunkStyles.argb(22, 0x4F6F8F), 0.02);
				}
			}
		}
		drawSharedChunkFlags(sink, timeSec, timeMs);
	}

	@Override
	protected int sharedFlagColor(long key, double timeSec) {
		return staleColor(key, colorArgb(timeSec));
	}

	/** True when any chunk within {@code distance} has a long open column. */
	private boolean hasVerticalShaftNear(long key, int distance) {
		if (level == null) {
			return false;
		}
		int cx = Chunks.x(key);
		int cz = Chunks.z(key);
		for (int dx = -distance; dx <= distance; dx++) {
			for (int dz = -distance; dz <= distance; dz++) {
				if (columnHasShaft(cx + dx, cz + dz)) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean columnHasShaft(int cx, int cz) {
		int x = cx * 16 + 8;
		int z = cz * 16 + 8;
		int start = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z) - 1;
		int min = Math.max(level.getMinY(), start - 48);
		int length = Math.max(1, start - min);
		boolean[] air = new boolean[length];
		for (int i = 0; i < length; i++) {
			air[i] = level.getBlockState(probe.set(x, start - i, z)).isAir();
		}
		return FinderRules.hasVerticalRun(air, 10);
	}

	@Override
	protected void onChunkUnloaded(long key) {
		if (engine != null) {
			engine.forget(key);
		}
		renderChunks.remove(key);
		dropTerrain(key);
		if (scanning && scanKey == key) {
			clearScan();
		}
	}

	private void clearScan() {
		scanning = false;
		scanKey = 0L;
		scanSection = 0;
		scanOwed = 0;
		scanBlossomRight = 0;
		scanBlossomWrong = 0;
	}

	@Override
	public void resetSession() {
		super.resetSession();
		engine = null;
		renderChunks.clear();
		terrain.clear();
		lastEvaluateTick = -1L;
		appliedCustom = -1;
		clearScan();
	}
}
