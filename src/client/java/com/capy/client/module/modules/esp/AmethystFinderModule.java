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
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
 * {@code amethyst_cluster} block is collected, grouped into the sixteen block
 * cell it sits in, and a cell that holds at least {@code threshold} clusters is
 * a geode. Counting the clusters rather than scanning for budding amethyst
 * matters because budding blocks sit inside the shell and are not what the
 * player is mining; the clusters are the visible, harvestable proof, and a
 * geode always has several.
 *
 * <h2>The bypass</h2>
 * A geode search is only useful on a server that actually sends the clusters,
 * and on the networks this module is built for the clusters are hidden from a
 * distance: the block is simply not in the chunk data until the player is close
 * enough to see it. Scanning further out therefore does nothing except waste
 * ticks and produce stale boxes, so the scan never looks past
 * {@link #BYPASS_RADIUS} blocks. Standing inside a geode is exactly the
 * situation the bypass exists for, and there the clusters are all present. It
 * is automatic - there is no switch to get wrong - and it is also why the boxes
 * vanish when the player walks away: what is no longer sent is no longer shown.
 *
 * <h2>What it draws</h2>
 * Each cluster gets a box in the module's colour, and every geode that reaches
 * the threshold gets one larger box in the geode colour, so a cluster that is
 * part of a find is distinguishable from one that is not. Both go through the
 * shared overlay renderer, which means the fill, the outline, the tracers and
 * the range all behave exactly like the other finders, and a new geode is
 * announced in chat once with its coordinates.
 */
public final class AmethystFinderModule extends EspModule {
	/** Ticks between two scans. */
	private static final int REFRESH_TICKS = 20;
	/** Tiny growth so an outline is not coplanar with the block it wraps. */
	private static final double INFLATE = 0.003;
	/** Growth on a geode box, so it reads as a region and not as a block. */
	private static final double GEODE_INFLATE = 0.15;
	/** How far out clusters are counted. See the class documentation. */
	private static final int BYPASS_RADIUS = 24;
	/** Most geodes remembered for the chat line before the memory is dropped. */
	private static final int MAX_ANNOUNCED = 256;

	private final SliderSetting threshold = setting(new SliderSetting("threshold", 3, 3, 5));
	private final BooleanSetting showCluster = setting(new BooleanSetting("show_cluster", true));
	private final BooleanSetting announce = setting(new BooleanSetting("announce", true));
	private final ColorSetting geodeColor = setting(new ColorSetting("geode_color", 0xFFB07CFF));

	private final List<AABB> boxes = new ArrayList<>();
	private final List<AABB> scratch = new ArrayList<>();
	/** The geode boxes among {@link #boxes}, by identity, for the colour. */
	private final Set<AABB> geodes = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Set<AABB> scratchGeodes = Collections.newSetFromMap(new IdentityHashMap<>());
	/** Cells whose find has already been announced, so chat says it once. */
	private final Set<Long> announced = new HashSet<>();
	private final Predicate<BlockState> isCluster = state -> state.is(Blocks.AMETHYST_CLUSTER);
	private ClientLevel level;
	private int cooldown;
	private int lastThreshold = -1;
	private int lastRange = -1;

	public AmethystFinderModule() {
		super(Category.BASE_FINDER, "amethyst_finder", 0xFFB07CFF, 32, false);
		// The threshold, the cluster switch, the announcement and the geode
		// colour are what this card is opened for; the shared overlay rows come
		// after them.
		List<Setting<?>> all = settings();
		List<Setting<?>> head = List.of(threshold, showCluster, announce, geodeColor);
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
		return geodes.contains(box) ? geodeColor.argb() : super.colorFor(box);
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
			// A new world: every cached position, and every announced find,
			// belongs to the old one.
			level = current;
			boxes.clear();
			scratch.clear();
			geodes.clear();
			scratchGeodes.clear();
			announced.clear();
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
		// The bypass: never look further than the server is willing to show.
		int limit = Math.min(range(), BYPASS_RADIUS);
		double limitSq = (double) limit * limit;
		int minY = level.getMinY();
		double px = mc.player.getX();
		double py = mc.player.getY();
		double pz = mc.player.getZ();
		int lowY = center.getY() - limit;
		int highY = center.getY() + limit;
		Map<Long, List<BlockPos>> found = new HashMap<>();

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
				// comparisons, which is what keeps the scan cheap.
				if (!section.maybeHas(isCluster)) {
					continue;
				}
				for (int y = 0; y < 16; y++) {
					int worldY = sectionY + y;
					if (worldY < lowY || worldY > highY) {
						continue;
					}
					for (int z = 0; z < 16; z++) {
						for (int x = 0; x < 16; x++) {
							if (!section.getBlockState(x, y, z).is(Blocks.AMETHYST_CLUSTER)) {
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
		for (Map.Entry<Long, List<BlockPos>> entry : found.entrySet()) {
			List<BlockPos> clusters = entry.getValue();
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
			AABB geode = union(clusters).inflate(GEODE_INFLATE);
			scratchGeodes.add(geode);
			scratch.add(geode);
			if (announce.value()) {
				announce(entry.getKey(), geode);
			}
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

	/** Says where a new geode is, once per geode per world. */
	private void announce(long cell, AABB geode) {
		if (!announced.add(cell)) {
			return;
		}
		if (announced.size() > MAX_ANNOUNCED) {
			announced.clear();
			announced.add(cell);
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.player == null) {
			return;
		}
		String text = String.format("Amethyst geode at %d %d %d",
				Math.round((geode.minX + geode.maxX) * 0.5),
				Math.round((geode.minY + geode.maxY) * 0.5),
				Math.round((geode.minZ + geode.maxZ) * 0.5));
		mc.player.displayClientMessage(Component.literal("[Capy] " + text), false);
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
		announced.clear();
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
		announce.set(ConfigValues.boolValue(json, "announce", true));
		geodeColor.set(ConfigValues.intValue(json, "geode_color", 0xFFB07CFF));
		cooldown = 0;
		lastThreshold = -1;
		lastRange = -1;
	}

	@Override
	public void saveConfig(JsonObject json) {
		super.saveConfig(json);
		json.addProperty("threshold", threshold.get());
		json.addProperty("show_cluster", showCluster.value());
		json.addProperty("announce", announce.value());
		json.addProperty("geode_color", geodeColor.argb());
	}
}
