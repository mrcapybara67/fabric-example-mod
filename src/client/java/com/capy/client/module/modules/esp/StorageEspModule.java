package com.capy.client.module.modules.esp;

import com.capy.client.config.ConfigValues;
import com.capy.client.module.ColorSetting;
import com.capy.client.module.Setting;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

/**
 * Storage ESP — a box around every container in range, in the colour of the
 * container it is.
 *
 * The scan reads the block entities the client already holds, so it costs one
 * map walk per loaded chunk and never asks the server for anything. It runs
 * once a second rather than once a frame: containers are placed and broken by
 * people, not by the renderer, and a box that is a second late is invisible
 * compared to a scan every frame.
 *
 * A container is anything whose block entity actually stores items, which in
 * vanilla is exactly the block entities that implement {@link Container}:
 * chests, barrels, shulker boxes, furnaces, hoppers, dispensers, brewing
 * stands, pots and bookshelves all do. Two kinds are added back by block id: an
 * ender chest, which holds its items in the player instead of in the world, and
 * a monster spawner, which holds nothing at all but is the block a mob farm is
 * built around.
 *
 * Each box keeps its {@link StorageKind} so the renderer can pick that kind's
 * colour for both the fill and the outline. The map is identity based on
 * purpose: the boxes are rebuilt every scan, so the reference is the key and
 * neither side has to hash a floating point box.
 */
public final class StorageEspModule extends EspModule {
	/** Ticks between two scans. */
	private static final int REFRESH_TICKS = 20;
	/** Tiny growth so an outline is not coplanar with the block it wraps. */
	private static final double INFLATE = 0.003;
	/**
	 * The two states the piston walk publishes.
	 *
	 * <p>Read through the section palette, exactly like the block overlay's
	 * filter: a section of plain stone answers this without walking a single
	 * block, so this walk can cover the same range as the container scan. It used
	 * to read one block state per position in a fifty block disc, which is why it
	 * was capped at 48 blocks - the cap is gone, the cost is.</p>
	 */
	private final Predicate<BlockState> isPiston =
			state -> state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON);

	private final List<AABB> boxes = new ArrayList<>();
	private final List<AABB> scratch = new ArrayList<>();
	/** One colour row per kind, in enum order so the card is stable. */
	private final Map<StorageKind, ColorSetting> colors = new java.util.EnumMap<>(StorageKind.class);
	/** The kind of every published box. Cleared and rebuilt with the boxes. */
	private final Map<AABB, StorageKind> kinds = new IdentityHashMap<>();
	private final Map<AABB, StorageKind> scratchKinds = new IdentityHashMap<>();
	private ClientLevel level;
	private int cooldown;
	private int lastRange = -1;

	public StorageEspModule() {
		// The whole view distance by default. The container walk is one map read
		// per loaded chunk, and the piston walk is palette driven, so distance
		// costs almost nothing here.
		super("storage_esp", MAX_RANGE, false);
		for (StorageKind kind : StorageKind.values()) {
			colors.put(kind, setting(new ColorSetting(kind.settingKey(), kind.defaultColor())));
		}
		// The colour rows belong at the top of the card, where every other
		// overlay keeps its colour: they are the settings this module is
		// opened for, and the core rows below them are shared with the other
		// two overlays anyway.
		List<Setting<?>> settings = settings();
		List<Setting<?>> colours = new ArrayList<>(colors.values());
		settings.removeAll(colours);
		settings.addAll(0, colours);
	}

	@Override
	public List<AABB> boxes() {
		return boxes;
	}

	/** The representative colour: the card's accent bar uses it. */
	@Override
	public int color() {
		ColorSetting chest = colors.get(StorageKind.CHEST);
		return chest == null ? super.color() : chest.argb();
	}

	/** The colour of this kind of storage, falling back to the base colour. */
	@Override
	public int colorFor(AABB box) {
		StorageKind kind = kinds.get(box);
		ColorSetting setting = kind == null ? null : colors.get(kind);
		return setting == null ? super.colorFor(box) : setting.argb();
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
			kinds.clear();
			scratchKinds.clear();
			cooldown = 0;
			return;
		}
		if (current != level) {
			// A new world: every cached position belongs to the old one.
			level = current;
			boxes.clear();
			scratch.clear();
			kinds.clear();
			scratchKinds.clear();
			cooldown = 0;
		}
		boolean rangeChanged = range() != lastRange;
		if (--cooldown > 0 && !rangeChanged) {
			return;
		}
		lastRange = range();
		cooldown = REFRESH_TICKS;
		scan(current, mc.player.blockPosition());
	}

	private void scan(ClientLevel level, BlockPos center) {
		scratch.clear();
		scratchKinds.clear();
		int limit = range();
		double limitSq = (double) limit * limit;
		double cx = center.getX() + 0.5;
		double cy = center.getY() + 0.5;
		double cz = center.getZ() + 0.5;
		EspScanner.forEachChunk(level, center, limit, chunk -> {
			for (BlockEntity entity : chunk.getBlockEntities().values()) {
				if (!isStorage(entity)) {
					continue;
				}
				BlockPos pos = entity.getBlockPos();
				if (EspScanner.distanceSqr(pos, cx, cy, cz) > limitSq) {
					continue;
				}
				AABB box = new AABB(pos).inflate(INFLATE);
				scratch.add(box);
				scratchKinds.put(box, StorageKind.of(entity.getBlockState()));
				if (scratch.size() >= MAX_BOXES) {
					return false;
				}
			}
			return true;
		});
		scanPistons(level, center, limit);
		publish();
	}

	/**
	 * Adds the piston family, which the block entity walk above can never see.
	 *
	 * <p>A piston stores nothing, so it has no {@link BlockEntity} and is
	 * invisible to a scan built on one. It is exactly the block a hidden door is
	 * made of, so this walk covers the same range as the container scan, in a
	 * band above and below the player rather than through the whole column: a
	 * piston door is at the player's level, and scanning from bedrock to build
	 * limit would cost twenty times as much for nothing.</p>
	 *
	 * <p>The palette check is what keeps this from highlighting every block in
	 * the world: a section with no piston in its palette is skipped whole, and of
	 * the sections that are walked only the two piston states are published.</p>
	 */
	private void scanPistons(ClientLevel level, BlockPos center, int limit) {
		double limitSq = (double) limit * limit;
		int minY = level.getMinY();
		int centerX = center.getX();
		int centerY = center.getY();
		int centerZ = center.getZ();
		int lowY = Math.max(minY, centerY - limit);
		int highY = Math.min(level.getMaxY() - 1, centerY + limit);
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
				if (sectionY + 16 <= lowY || sectionY > highY) {
					continue;
				}
				if (!section.maybeHas(isPiston)) {
					continue;
				}
				for (int y = 0; y < 16; y++) {
					int worldY = sectionY + y;
					if (worldY < lowY || worldY > highY) {
						continue;
					}
					int dy = worldY - centerY;
					for (int z = 0; z < 16; z++) {
						int worldZ = baseZ + z;
						int dz = worldZ - centerZ;
						for (int x = 0; x < 16; x++) {
							StorageKind kind = StorageKind.of(section.getBlockState(x, y, z));
							if (kind != StorageKind.PISTON && kind != StorageKind.STICKY_PISTON) {
								continue;
							}
							int worldX = baseX + x;
							int dx = worldX - centerX;
							if ((double) dx * dx + (double) dy * dy + (double) dz * dz > limitSq) {
								continue;
							}
							if (scratch.size() >= MAX_BOXES) {
								return false;
							}
							AABB box = new AABB(worldX, worldY, worldZ,
									worldX + 1.0, worldY + 1.0, worldZ + 1.0).inflate(INFLATE);
							scratch.add(box);
							scratchKinds.put(box, kind);
						}
					}
				}
			}
			return true;
		});
	}

	/** Copies the fresh scan into the published lists without reallocating them. */
	private void publish() {
		boxes.clear();
		boxes.addAll(scratch);
		kinds.clear();
		kinds.putAll(scratchKinds);
	}

	private static boolean isStorage(BlockEntity entity) {
		// The ender chest holds its items in the player, so its block entity is
		// not a Container even though it is unmistakably storage. A spawner is
		// named by block id for a different reason: it stores no items, so
		// whether its block entity counts as a Container is an implementation
		// detail of the version - the block is what the player is looking for.
		return entity instanceof Container
				|| entity.getBlockState().is(Blocks.ENDER_CHEST)
				|| entity.getBlockState().is(Blocks.SPAWNER);
	}

	@Override
	protected void onToggle(boolean state) {
		super.onToggle(state);
		cooldown = 0;
		lastRange = -1;
	}

	@Override
	protected void clear() {
		boxes.clear();
		scratch.clear();
		kinds.clear();
		scratchKinds.clear();
		level = null;
		cooldown = 0;
		lastRange = -1;
	}

	// ---------------------------------------------------------- persistence

	@Override
	public void loadConfig(JsonObject json) {
		super.loadConfig(json);
		for (Map.Entry<StorageKind, ColorSetting> entry : colors.entrySet()) {
			entry.getValue().set(ConfigValues.intValue(json, entry.getKey().settingKey(),
					entry.getKey().defaultColor()));
		}
		// The range may have changed between sessions; scan on the next tick.
		lastRange = -1;
		cooldown = 0;
	}

	@Override
	public void saveConfig(JsonObject json) {
		super.saveConfig(json);
		for (Map.Entry<StorageKind, ColorSetting> entry : colors.entrySet()) {
			json.addProperty(entry.getKey().settingKey(), entry.getValue().argb());
		}
	}
}
