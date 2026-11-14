package com.capy.client.module.modules.esp;

import com.capy.client.finder.core.ChunkStyles;
import com.capy.client.finder.core.Chunks;
import com.capy.client.finder.core.DrawSink;
import com.capy.client.finder.core.GrowthRules;
import com.capy.client.finder.core.PocketFilter;
import com.capy.client.finder.core.SusChunkEngine;
import com.capy.client.mixin.ClientCommonPacketListenerAccessor;
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
 * <p>Snow and ice are deliberately not evidence. Their melt and freeze ticks
 * depend on biome temperature and light, which a client cannot resolve from a
 * block state alone; treating the wrong ice as an owed tick would create a
 * false base. Everything else is evaluated type-first, so a section full of
 * leaves or crops does not read thousands of neighbours.</p>
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
				|| state.getBlock() instanceof CropBlock || state.getBlock() instanceof LeavesBlock;
	};

	private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
	private final Set<Long> renderChunks = new HashSet<>();
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
		super("stalled_growth_finder", 0xFFFF5A5A);
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!beginTick(mc)) {
			return;
		}
		ensureEngine();
		long tick = level.getGameTime();
		if (lastEvaluateTick < 0L || tick - lastEvaluateTick >= EVALUATE_INTERVAL_TICKS) {
			lastEvaluateTick = tick;
			evaluate();
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

		Boolean blossomOpen = eyeblossomsJudgeable()
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

	private void ensureEngine() {
		if (level == null) {
			return;
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
		}
	}

	private int evidenceRadius() {
		int sent = level.getServerSimulationDistance();
		if (sent <= 0) {
			Minecraft mc = Minecraft.getInstance();
			int view = mc == null ? 8 : mc.options.getEffectiveRenderDistance();
			sent = Math.min(Math.max(0, view), 8);
		}
		return Math.max(1, Math.min(32, sent));
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
		return false;
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
		Set<Long> found = engine.flagged(this::loaded, SusChunkEngine.thresholdFor(engine.radius()));
		List<PocketFilter.Patch> patches = PocketFilter.patches(found, PocketFilter.maxSizeFor(engine.radius()));

		clearFlags();
		renderChunks.clear();
		for (PocketFilter.Patch patch : patches) {
			long center = patch.center();
			int support = engine.supportAt(center);
			String reason = "possible player area, evidence " + support + ", "
					+ patch.size() + " chunk(s)";
			markFlag(center, reason, support, List.of(surfaceBox(center)));
			renderChunks.addAll(patch.members());
		}
	}

	@Override
	public void drawFlags(DrawSink sink, double timeSec, double timeMs) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || level == null) {
			return;
		}
		double y = Math.floor(mc.player.getY()) + 0.05;
		if (eliminationMapVisible() && engine != null) {
			Set<Long> cleared = engine.clearedChunks();
			if (!cleared.isEmpty()) {
				ChunkStyles.fillContour(sink, cleared, y - 0.02, 0x4F6F8F, 18, 0, timeSec);
			}
		}
		if (renderChunks.isEmpty()) {
			return;
		}
		int argb = colorArgb();
		int fill = Math.round((argb >>> 24) * 0.40f);
		ChunkStyles.fillContour(sink, renderChunks, y, argb & 0xFFFFFF,
				fill, Math.min(255, Math.max(170, argb >>> 24)), timeSec);
	}

	@Override
	public String statusLine() {
		int scanned = FinderScanner.get().scannedCount(this);
		int stalled = engine == null ? 0 : engine.stalledChunks();
		int found = flags.size();
		String prefix = "scanned " + scanned + ", stalled " + stalled + ", flags " + found;
		if (found > 0) {
			return prefix;
		}
		if (scanned == 0) {
			return prefix + " - waiting for chunks";
		}
		return prefix + " - " + (stalled == 0 ? "no plants found" : "no enclosed area");
	}

	@Override
	protected void onChunkUnloaded(long key) {
		if (engine != null) {
			engine.forget(key);
		}
		renderChunks.remove(key);
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
		lastEvaluateTick = -1L;
		clearScan();
	}
}
