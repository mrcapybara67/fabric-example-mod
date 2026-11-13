package com.capy.client.module.modules.esp;

import com.capy.client.finder.core.Chunks;
import com.capy.client.finder.core.GrowthRules;
import com.capy.client.finder.core.SusChunkEngine;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
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
 * Scanning is one chunk per slice and one 2 ms slice per client tick.</p>
 *
 * <p>The server's simulation distance comes from the login state held by
 * {@link net.minecraft.client.multiplayer.ClientLevel}; no assumption about the
 * view distance is needed. Amethyst is deliberately not fed into this scanner:
 * the existing Amethyst Finder is kept as it is.</p>
 */
public final class StalledGrowthFinderModule extends FinderModule {
	private static final long SCAN_BUDGET_NANOS = 2_000_000L;
	private static final long RESCAN_INTERVAL_TICKS = 1200L;
	private static final long EVALUATE_INTERVAL_TICKS = 20L;
	private static final Predicate<BlockState> GROWTH = state -> {
		if (state == null) {
			return false;
		}
		return state.is(Blocks.KELP) || state.is(Blocks.WEEPING_VINES) || state.is(Blocks.TWISTING_VINES)
				|| state.is(Blocks.BAMBOO) || state.is(Blocks.SUGAR_CANE) || state.is(Blocks.CACTUS)
				|| state.is(Blocks.COCOA) || state.is(Blocks.NETHER_WART) || state.is(Blocks.SWEET_BERRY_BUSH)
				|| state.is(Blocks.MELON_STEM) || state.is(Blocks.PUMPKIN_STEM) || state.is(Blocks.VINE)
				|| state.is(Blocks.MANGROVE_PROPAGULE)
				|| state.getBlock() instanceof CropBlock || state.getBlock() instanceof LeavesBlock;
	};

	private final ArrayDeque<Long> queue = new ArrayDeque<>();
	private final Set<Long> queued = new HashSet<>();
	private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
	private SusChunkEngine engine;
	private long lastEnqueueTick = -1L;
	private long lastEvaluateTick = -1L;

	public StalledGrowthFinderModule() {
		super("stalled_growth_finder", 0xFF8FB8FF);
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!beginTick(mc)) {
			return;
		}
		int simulation = simulationDistance();
		if (engine == null || engine.radius() != simulation) {
			engine = new SusChunkEngine(simulation);
			// A different elimination radius makes the old flags invalid.
			replaceFlags(Map.of());
		}
		long tick = level.getGameTime();
		if (lastEnqueueTick < 0L || tick - lastEnqueueTick >= RESCAN_INTERVAL_TICKS) {
			enqueueLoaded();
			lastEnqueueTick = tick;
		}
		long deadline = System.nanoTime() + SCAN_BUDGET_NANOS;
		while (!queue.isEmpty() && System.nanoTime() < deadline) {
			long key = queue.pollFirst();
			queued.remove(key);
			LevelChunk chunk = level.getChunkSource().getChunkNow(Chunks.x(key), Chunks.z(key));
			if (chunk != null) {
				engine.report(key, scanChunk(chunk));
			}
		}
		if (tick - lastEvaluateTick >= EVALUATE_INTERVAL_TICKS) {
			lastEvaluateTick = tick;
			evaluate();
		}
	}

	private void enqueueLoaded() {
		int radius = Math.min(16, simulationDistance() + 4);
		int cx = Minecraft.getInstance().player.blockPosition().getX() >> 4;
		int cz = Minecraft.getInstance().player.blockPosition().getZ() >> 4;
		for (int dz = -radius; dz <= radius; dz++) {
			for (int dx = -radius; dx <= radius; dx++) {
				int x = cx + dx;
				int z = cz + dz;
				if (level.getChunkSource().getChunkNow(x, z) == null) {
					continue;
				}
				long key = Chunks.key(x, z);
				if (queued.add(key)) {
					queue.addLast(key);
				}
			}
		}
	}

	private int scanChunk(LevelChunk chunk) {
		int owed = 0;
		int minY = chunk.getMinY();
		LevelChunkSection[] sections = chunk.getSections();
		int baseX = chunk.getPos().getMinBlockX();
		int baseZ = chunk.getPos().getMinBlockZ();
		for (int index = 0; index < sections.length; index++) {
			LevelChunkSection section = sections[index];
			if (section == null || section.hasOnlyAir() || !section.maybeHas(GROWTH)) {
				continue;
			}
			int sectionY = minY + (index << 4);
			for (int y = 0; y < 16; y++) {
				int worldY = sectionY + y;
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = section.getBlockState(x, y, z);
						if (isOwed(state, baseX + x, worldY, baseZ + z)) {
							owed++;
						}
					}
				}
			}
		}
		return owed;
	}

	private boolean isOwed(BlockState state, int x, int y, int z) {
		if (state == null) {
			return false;
		}
		BlockState above = level.getBlockState(probe.set(x, y + 1, z));
		if (state.is(Blocks.KELP)) {
			return GrowthRules.kelp(state.getValue(GrowingPlantHeadBlock.AGE),
					above.getFluidState().is(FluidTags.WATER));
		}
		if (state.getBlock() instanceof WeepingVinesBlock || state.getBlock() instanceof TwistingVinesBlock) {
			BlockState below = level.getBlockState(probe.set(x, y - 1, z));
			return GrowthRules.hangingVine(state.getValue(GrowingPlantHeadBlock.AGE), below.isAir());
		}
		if (state.is(Blocks.BAMBOO)) {
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
			return GrowthRules.sugarCane(above.isAir(), caneHeight(x, y, z));
		}
		if (state.is(Blocks.CACTUS)) {
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
		return false;
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

	private void evaluate() {
		if (engine == null || level == null || Minecraft.getInstance().player == null) {
			return;
		}
		int cx = Minecraft.getInstance().player.blockPosition().getX() >> 4;
		int cz = Minecraft.getInstance().player.blockPosition().getZ() >> 4;
		engine.prune(cx, cz, Math.min(64, engine.reach() + 8));
		Set<Long> found = engine.flagged(this::loaded, SusChunkEngine.thresholdFor(engine.radius()));
		for (long key : found) {
			if (!flags.contains(key)) {
				markFlag(key, List.of(surfaceBox(key)));
			}
		}
	}

	@Override
	public void resetSession() {
		super.resetSession();
		queue.clear();
		queued.clear();
		engine = null;
		lastEnqueueTick = -1L;
		lastEvaluateTick = -1L;
	}
}
