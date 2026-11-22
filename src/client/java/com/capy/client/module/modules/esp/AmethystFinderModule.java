package com.capy.client.module.modules.esp;

import com.capy.client.config.ConfigValues;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.Category;
import com.capy.client.module.ColorSetting;
import com.capy.client.module.Setting;
import com.capy.client.module.SliderSetting;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

/**
 * Amethyst Finder — finds geodes by counting the clusters that are fully grown
 * inside them.
 *
 * <h2>How a geode is found</h2>
 * A geode is recognised by what it is full of, not by a structure table: every
 * {@code amethyst_cluster} block is collected, nearby clusters are merged into
 * one group, and a group with at least {@code threshold} clusters is a geode.
 * The clusters are the visible, harvestable proof. A group must also still
 * have {@code budding_amethyst} in its shell: a natural geode always keeps
 * some, while a wall of placed clusters without one is decoration rather than
 * a find.
 *
 * <h2>How far it looks</h2>
 * As far as the client has chunks. A cluster the client was never sent cannot
 * be drawn by any client, so the walk covers every loaded chunk inside the
 * module's range - the whole view distance by default - and nothing further.
 * There is no shorter cap and no switch: an earlier version stopped at 24
 * blocks, which meant a geode forty blocks away was invisible until the player
 * walked up to it even though its clusters were already in memory.
 *
 * <h2>What it draws</h2>
 * Each cluster gets a box in the module's colour, and every geode that reaches
 * the threshold gets one larger box in the geode colour, so a cluster that is
 * part of a find is distinguishable from one that is not. Both go through the
 * shared overlay renderer, which means the fill, the outline, the tracers and
 * the range all behave exactly like the other finders. It never prints to chat
 * and never creates a toast.
 */
public final class AmethystFinderModule extends EspModule {
	/** Ticks between two scans. */
	private static final int REFRESH_TICKS = 20;
	/** Tiny growth so an outline is not coplanar with the block it wraps. */
	private static final double INFLATE = 0.003;
	/** Growth on a geode box, so it reads as a region and not as a block. */
	private static final double GEODE_INFLATE = 0.15;
	/**
	 * How close two clusters have to be to count as the same geode.
	 *
	 * <p>A geode is about fifteen blocks across, so its clusters regularly sit
	 * either side of the sixteen block cell they used to be counted in. Counting
	 * per cell split such a geode in half and a threshold of three was then
	 * reached on neither side - a geode full of clusters that the finder never
	 * reported. Groups are merged by distance instead of by cell.
	 */
	private static final double MERGE_GAP = 8.0;
	private final SliderSetting threshold = setting(new SliderSetting("threshold", 3, 3, 5));
	private final BooleanSetting showCluster = setting(new BooleanSetting("show_cluster", true));
	private final ColorSetting geodeColor = setting(new ColorSetting("geode_color", 0xFFB07CFF));

	private final List<AABB> boxes = new ArrayList<>();
	private final List<AABB> scratch = new ArrayList<>();
	/** The geode boxes among {@link #boxes}, by identity, for the colour. */
	private final Set<AABB> geodes = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Set<AABB> scratchGeodes = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Predicate<BlockState> isCluster = state -> state.is(Blocks.AMETHYST_CLUSTER);
	private final Predicate<BlockState> isBudding = state -> state.is(Blocks.BUDDING_AMETHYST);
	private ClientLevel level;
	private int cooldown;
	private int lastThreshold = -1;
	private int lastRange = -1;

	public AmethystFinderModule() {
		super(Category.BASE_FINDER, "amethyst_finder", 0xFFB07CFF, MAX_RANGE, false);
		// The threshold, the cluster switch and the geode colour are what this
		// card is opened for; the shared overlay rows come after them.
		List<Setting<?>> all = settings();
		List<Setting<?>> head = List.of(threshold, showCluster, geodeColor);
		all.removeAll(head);
		all.addAll(0, head);
	}

	@Override
	public List<AABB> boxes() {
		return boxes;
	}

	/** Geodes are drawn in their own colour, clusters in the module's. */
	@Override
	public int colorFor(AABB box) {
		return colorFor(box, System.nanoTime() / 1.0e9);
	}

	@Override
	public int colorFor(AABB box, double timeSec) {
		return geodes.contains(box) ? geodeColor.argbAt(timeSec) : super.colorFor(box, timeSec);
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!isEnabled()) {
			return;
		}
		ClientLevel current = mc.level;
		if (current == null || mc.player == null) {
			level = null;
			boxes.clear();
			scratch.clear();
			geodes.clear();
			scratchGeodes.clear();
			cooldown = 0;
			return;
		}
		if (current != level) {
			// A new world: every cached position belongs to the old one.
			level = current;
			boxes.clear();
			scratch.clear();
			geodes.clear();
			scratchGeodes.clear();
			cooldown = 0;
		}
		boolean changed = threshold.get() != lastThreshold || range() != lastRange;
		if (changed) {
			lastThreshold = threshold.get();
			lastRange = range();
			cooldown = 0;
		}
		if (--cooldown > 0) {
			return;
		}
		cooldown = REFRESH_TICKS;
		scan(current, mc);
	}

	private void scan(ClientLevel level, Minecraft mc) {
		scratch.clear();
		scratchGeodes.clear();
		BlockPos center = mc.player.blockPosition();
		// Every loaded chunk inside the range. What the server never sent is
		// simply not there, so this is the largest useful walk, not a guess.
		int limit = range();
		double limitSq = (double) limit * limit;
		int minY = level.getMinY();
		double px = mc.player.getX();
		double py = mc.player.getY();
		double pz = mc.player.getZ();
		int lowY = center.getY() - limit;
		int highY = center.getY() + limit;
		Map<Long, List<BlockPos>> found = new HashMap<>();
		List<BlockPos> budding = new ArrayList<>();

		EspScanner.forEachChunk(level, center, limit, chunk -> {
			LevelChunkSection[] sections = chunk.getSections();
			int baseX = chunk.getPos().getMinBlockX();
			int baseZ = chunk.getPos().getMinBlockZ();
			for (int index = 0; index < sections.length; index++) {
				LevelChunkSection section = sections[index];
				if (section == null || section.hasOnlyAir()) {
					continue;
				}
				int sectionY = minY + (index << 4);
				// Skip sections that are entirely above or below the player.
				if (sectionY + 16 <= lowY || sectionY > highY) {
					continue;
				}
				// A section of plain stone answers this in a handful of
				// comparisons, which is what keeps the scan cheap. Budding
				// amethyst is read in the same walk: it is the proof that a
				// cluster group is a real geode rather than placed decoration.
				boolean clusters = section.maybeHas(isCluster);
				boolean buds = section.maybeHas(isBudding);
				if (!clusters && !buds) {
					continue;
				}
				for (int y = 0; y < 16; y++) {
					int worldY = sectionY + y;
					if (worldY < lowY || worldY > highY) {
						continue;
					}
					for (int z = 0; z < 16; z++) {
						for (int x = 0; x < 16; x++) {
							// A section can answer with null while it is still being
							// filled from the server; asking a null state what block it is
							// would end the scan with a crash instead of skipping a
							// column. One comparison per block is not measurable.
							BlockState state = section.getBlockState(x, y, z);
							if (state == null) {
								continue;
							}
							if (buds && state.is(Blocks.BUDDING_AMETHYST)) {
								budding.add(new BlockPos(baseX + x, worldY, baseZ + z));
							}
							if (!clusters || !state.is(Blocks.AMETHYST_CLUSTER)) {
								continue;
							}
							BlockPos pos = new BlockPos(baseX + x, worldY, baseZ + z);
							if (EspScanner.distanceSqr(pos, px, py, pz) > limitSq) {
								continue;
							}
							found.computeIfAbsent(cellKey(pos), key -> new ArrayList<>()).add(pos);
						}
					}
				}
			}
			return true;
		});

		int need = Math.max(3, Math.min(5, threshold.get()));
		// Merge the per-cell buckets into one group per geode, by proximity.
		List<List<BlockPos>> groups = new ArrayList<>();
		List<AABB> groupBounds = new ArrayList<>();
		for (List<BlockPos> clusters : found.values()) {
			for (BlockPos pos : clusters) {
				AABB box = new AABB(pos);
				int join = -1;
				for (int i = 0; i < groupBounds.size(); i++) {
					if (groupBounds.get(i).inflate(MERGE_GAP).intersects(box)) {
						join = i;
						break;
					}
				}
				if (join < 0) {
					groups.add(new ArrayList<>());
					groupBounds.add(box);
					groups.get(groups.size() - 1).add(pos);
				} else {
					groups.get(join).add(pos);
					groupBounds.set(join, groupBounds.get(join).minmax(box));
				}
			}
		}
		// A new cluster can bridge two groups that were not close enough to
		// merge when they were created. Merge the resulting overlap before
		// applying the threshold, or one geode can be counted as two halves.
		mergeOverlappingGroups(groups, groupBounds);
		// A cluster group only becomes a geode when the shell around it still
		// holds budding amethyst. Natural geodes always do; a wall of placed
		// clusters without one is decoration, not a find.
		for (int i = groups.size() - 1; i >= 0; i--) {
			if (!holdsBudding(groupBounds.get(i), budding)) {
				groups.remove(i);
				groupBounds.remove(i);
			}
		}
		for (List<BlockPos> clusters : groups) {
			if (scratch.size() >= MAX_BOXES) {
				break;
			}
			if (showCluster.value()) {
				for (BlockPos pos : clusters) {
					if (scratch.size() >= MAX_BOXES) {
						break;
					}
					scratch.add(new AABB(pos).inflate(INFLATE));
				}
			}
			if (clusters.size() < need) {
				continue;
			}
			// The geode box counts against the cap too; previously it was added
			// after the break, so the published list could exceed MAX_BOXES.
			if (scratch.size() >= MAX_BOXES) {
				break;
			}
			AABB geode = union(clusters).inflate(GEODE_INFLATE);
			scratchGeodes.add(geode);
			scratch.add(geode);
		}
		publish();
	}

	/** Copies the fresh scan into the published lists without reallocating them. */
	private void publish() {
		boxes.clear();
		boxes.addAll(scratch);
		geodes.clear();
		geodes.addAll(scratchGeodes);
	}

	private static void mergeOverlappingGroups(List<List<BlockPos>> groups, List<AABB> bounds) {
		boolean merged = true;
		while (merged) {
			merged = false;
			for (int i = 0; i < bounds.size() && !merged; i++) {
				for (int j = i + 1; j < bounds.size(); j++) {
					if (!bounds.get(i).inflate(MERGE_GAP).intersects(bounds.get(j))) {
						continue;
					}
					AABB other = bounds.remove(j);
					groups.get(i).addAll(groups.remove(j));
					bounds.set(i, bounds.get(i).minmax(other));
					merged = true;
					break;
				}
			}
		}
	}

	/** True when a geode shell near this cluster group still holds budding amethyst. */
	private static boolean holdsBudding(AABB bounds, List<BlockPos> budding) {
		AABB near = bounds.inflate(MERGE_GAP);
		for (BlockPos pos : budding) {
			if (near.contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) {
				return true;
			}
		}
		return false;
	}

	/** The box around a whole group of clusters. */
	private static AABB union(List<BlockPos> positions) {
		double minX = Double.MAX_VALUE;
		double minY = Double.MAX_VALUE;
		double minZ = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE;
		double maxY = -Double.MAX_VALUE;
		double maxZ = -Double.MAX_VALUE;
		for (BlockPos pos : positions) {
			minX = Math.min(minX, pos.getX());
			minY = Math.min(minY, pos.getY());
			minZ = Math.min(minZ, pos.getZ());
			maxX = Math.max(maxX, pos.getX() + 1.0);
			maxY = Math.max(maxY, pos.getY() + 1.0);
			maxZ = Math.max(maxZ, pos.getZ() + 1.0);
		}
		if (minX > maxX) {
			return new AABB(0, 0, 0, 0, 0, 0);
		}
		return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
	}

	/**
	 * The sixteen block cell a cluster belongs to, packed into a long.
	 *
	 * A geode is roughly two chunks wide, so one cell holds one geode; the key
	 * only has to be stable and cheap, not exact.
	 */
	private static long cellKey(BlockPos pos) {
		long gx = (pos.getX() >> 4) & 0x3FFFFFL;
		long gy = (pos.getY() >> 4) & 0xFFFFFL;
		long gz = (pos.getZ() >> 4) & 0x3FFFFFL;
		return (gx << 42) | (gy << 22) | gz;
	}

	@Override
	protected void onToggle(boolean state) {
		super.onToggle(state);
		cooldown = 0;
		lastThreshold = -1;
		lastRange = -1;
	}

	@Override
	protected void clear() {
		boxes.clear();
		scratch.clear();
		geodes.clear();
		scratchGeodes.clear();
		level = null;
		cooldown = 0;
		lastThreshold = -1;
		lastRange = -1;
	}

	// ---------------------------------------------------------- persistence

	@Override
	public void loadConfig(JsonObject json) {
		super.loadConfig(json);
		threshold.set(ConfigValues.intValue(json, "threshold", 3));
		showCluster.set(ConfigValues.boolValue(json, "show_cluster", true));
		geodeColor.load(json, "geode_color");
		cooldown = 0;
		lastThreshold = -1;
		lastRange = -1;
	}

	@Override
	public void saveConfig(JsonObject json) {
		super.saveConfig(json);
		json.addProperty("threshold", threshold.get());
		json.addProperty("show_cluster", showCluster.value());
		geodeColor.save(json, "geode_color");
	}
}
