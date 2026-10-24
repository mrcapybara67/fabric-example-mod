package com.capy.client.module.modules.esp;

import com.capy.client.config.ConfigValues;
import com.capy.client.module.BlockListSetting;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

/**
 * Block ESP — a box around every block the user filtered for.
 *
 * Scanning chunks block by block would be tens of millions of lookups per
 * second, so the scan never does that. It first asks each chunk section whether
 * its palette contains one of the filtered blocks
 * ({@link LevelChunkSection#maybeHas}); a section of plain stone answers that
 * in a handful of comparisons and is skipped entirely. Only a section that does
 * contain a match is walked position by position, which is why an ore filter
 * costs nothing on a server where the player is standing in a field.
 *
 * The filter is resolved once per change into a set of {@link Block} objects,
 * so the hot loop compares references instead of parsing strings.
 */
public final class BlockEspModule extends EspModule {
	/** Ticks between two scans. */
	private static final int REFRESH_TICKS = 20;
	/** Tiny growth so an outline is not coplanar with the block it wraps. */
	private static final double INFLATE = 0.003;

	private final BlockListSetting blocks = setting(new BlockListSetting("blocks"));
	private final List<AABB> boxes = new ArrayList<>();
	private final List<AABB> scratch = new ArrayList<>();
	private final Set<Block> filter = new HashSet<>();
	/** Reused so the palette predicate never allocates inside the scan. */
	private final Predicate<BlockState> matches = state -> filter.contains(state.getBlock());

	private int filterSignature = Integer.MIN_VALUE;
	private ClientLevel level;
	private int cooldown;
	private int lastRange = -1;

	public BlockEspModule() {
		super("block_esp", 0xFF5CE1E6, 64);
		// A useful starting filter: the block ESP does something the first time
		// it is switched on, and the list editor shows how to change it.
		blocks.set(List.of("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore",
				"minecraft:ancient_debris"));
	}

	@Override
	public List<AABB> boxes() {
		return boxes;
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
			cooldown = 0;
			return;
		}
		if (current != level) {
			level = current;
			boxes.clear();
			scratch.clear();
			cooldown = 0;
		}
		boolean changed = refreshFilter();
		if (range() != lastRange) {
			changed = true;
			lastRange = range();
		}
		if (changed) {
			cooldown = 0;
		}
		if (--cooldown > 0) {
			return;
		}
		cooldown = REFRESH_TICKS;
		scan(current, mc.player.blockPosition());
	}

	/** Rebuilds the resolved filter when the list setting changed. */
	private boolean refreshFilter() {
		int signature = blocks.signature();
		if (signature == filterSignature) {
			return false;
		}
		filterSignature = signature;
		filter.clear();
		for (String entry : blocks.entries()) {
			Identifier id = Identifier.tryParse(entry);
			if (id != null) {
				BuiltInRegistries.BLOCK.getOptional(id).ifPresent(filter::add);
			}
		}
		return true;
	}

	private void scan(ClientLevel level, BlockPos center) {
		scratch.clear();
		if (filter.isEmpty()) {
			publish();
			return;
		}
		int limit = range();
		int limitSq = limit * limit;
		int minY = level.getMinY();
		int centerX = center.getX();
		int centerY = center.getY();
		int centerZ = center.getZ();
		int lowY = centerY - limit;
		int highY = centerY + limit;
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
				if (!section.maybeHas(matches)) {
					continue;
				}
				for (int y = 0; y < 16; y++) {
					int worldY = sectionY + y;
					if (worldY < lowY || worldY > highY) {
						continue;
					}
					for (int z = 0; z < 16; z++) {
						int worldZ = baseZ + z;
						int dz = worldZ - centerZ;
						for (int x = 0; x < 16; x++) {
							if (!filter.contains(section.getBlockState(x, y, z).getBlock())) {
								continue;
							}
							int worldX = baseX + x;
							int dx = worldX - centerX;
							if (dx * dx + dz * dz > limitSq) {
								continue;
							}
							int dy = worldY - centerY;
							if (dx * dx + dy * dy + dz * dz > limitSq) {
								continue;
							}
							scratch.add(new AABB(worldX, worldY, worldZ,
									worldX + 1, worldY + 1, worldZ + 1).inflate(INFLATE));
							if (scratch.size() >= MAX_BOXES) {
								return false;
							}
						}
					}
				}
			}
			return true;
		});
		publish();
	}

	/** Copies the fresh scan into the published list without reallocating it. */
	private void publish() {
		boxes.clear();
		boxes.addAll(scratch);
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
		level = null;
		cooldown = 0;
		lastRange = -1;
	}

	// ---------------------------------------------------------- persistence

	@Override
	public void loadConfig(JsonObject json) {
		super.loadConfig(json);
		if (json != null && json.has("blocks")) {
			blocks.set(ConfigValues.stringList(json, "blocks"));
		}
		filterSignature = Integer.MIN_VALUE;
		lastRange = -1;
		cooldown = 0;
	}

	@Override
	public void saveConfig(JsonObject json) {
		super.saveConfig(json);
		JsonArray array = new JsonArray();
		for (String entry : blocks.entries()) {
			array.add(entry);
		}
		json.add("blocks", array);
	}
}
