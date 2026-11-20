package com.capy.client.module.modules.esp;

import com.capy.client.config.ConfigValues;
import com.capy.client.finder.core.Chunks;
import com.capy.client.finder.core.DrawSink;
import com.capy.client.finder.core.FlagStyles;
import com.capy.client.module.BlockListSetting;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.IntSetting;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

/**
 * Block Notifier: finds the blocks on the user's list and outlines them.
 *
 * <p>The palette check happens before any cell is read, so a chunk of stone
 * costs one predicate pass per section. A found block gets an outlined box; no
 * toast or chat line is produced.</p>
 */
public final class BlockNotifierModule extends FinderModule {
	private static final double INFLATE = 0.003;

	private final BlockListSetting blocks = describe(setting(new BlockListSetting("blocks")),
			"The blocks to look for. A block id can be typed or pasted here.");
	private final IntSetting minCount = describe(setting(new IntSetting("min_count", 1, 1, 64, 1)),
			"How many of one block a chunk needs before it is flagged.");
	private final BooleanSetting placedBlocks = describe(setting(new BooleanSetting("placed_blocks", true)),
			"Notify when a listed block is placed near you.");
	private final BooleanSetting chatMessage = describe(setting(new BooleanSetting("chat_message", false)),
			"Write one chat line when a new block flag appears.");
	private int appliedMinCount = -1;
	private long lastPlacedScanTick = Long.MIN_VALUE;
	private final Set<Long> placedSeen = new HashSet<>();
	private final Set<Block> filter = new HashSet<>();
	private final Predicate<BlockState> matcher = state -> filter.contains(state.getBlock());
	private final Map<Long, List<AABB>> boxesByChunk = new HashMap<>();
	private final List<AABB> renderBoxes = new ArrayList<>();
	private final List<Found> scratch = new ArrayList<>();
	private boolean scanning;
	private long scanKey = Long.MIN_VALUE;
	private int scanSection;
	private int filterSignature = Integer.MIN_VALUE;

	public BlockNotifierModule() {
		super("block_notifier", 0xFFFFC857);
		blocks.set(defaultBlocks());
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!beginTick(mc)) {
			return;
		}
		if (refreshFilter()) {
			clearFlags();
			boxesByChunk.clear();
			scratch.clear();
			scanning = false;
			scanKey = Long.MIN_VALUE;
			scanSection = 0;
			rebuildRender();
			FinderScanner.get().queueAllLoaded();
		}
		if (appliedMinCount != minCount.get()) {
			appliedMinCount = minCount.get();
			clearFlags();
			boxesByChunk.clear();
			rebuildRender();
			FinderScanner.get().queueAllLoaded();
		}
		if (placedBlocks.value()) {
			scanPlaced(mc);
		} else {
			placedSeen.clear();
			lastPlacedScanTick = Long.MIN_VALUE;
		}
	}

	/** Watches the eight-block cube around the player for a newly placed listed block. */
	private void scanPlaced(Minecraft mc) {
		if (level == null || mc.player == null || filter.isEmpty()) {
			return;
		}
		long tick = level.getGameTime();
		if (lastPlacedScanTick != Long.MIN_VALUE && tick - lastPlacedScanTick < 20L) {
			return;
		}
		boolean first = lastPlacedScanTick == Long.MIN_VALUE;
		lastPlacedScanTick = tick;
		BlockPos center = mc.player.blockPosition();
		Set<Long> current = new HashSet<>();
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-8, -8, -8), center.offset(8, 8, 8))) {
			if (filter.contains(level.getBlockState(pos).getBlock())) {
				current.add(pos.asLong());
			}
		}
		if (!first) {
			for (long packed : current) {
				if (placedSeen.contains(packed)) {
					continue;
				}
				BlockPos pos = BlockPos.of(packed);
				String name = level.getBlockState(pos).getBlock().getName().getString();
				notify.toast(mc, "Block placed", name + " near you");
				if (chatMessage.value()) {
					notify.chat(mc, "Block Notifier: " + name + " was placed near you");
				}
				break;
			}
		}
		placedSeen.clear();
		placedSeen.addAll(current);
	}

	@Override
	public boolean scan(ClientLevel level, LevelChunk chunk, long deadlineNanos) {
		this.level = level;
		long key = Chunks.key(chunk.getPos().x, chunk.getPos().z);
		if (!scanning || scanKey != key) {
			scanning = true;
			scanKey = key;
			scanSection = 0;
			scratch.clear();
		}
		if (filter.isEmpty()) {
			replaceChunk(key, List.of(), List.of());
			clearScan();
			return true;
		}

		LevelChunkSection[] sections = chunk.getSections();
		int minY = chunk.getMinY();
		int baseX = chunk.getPos().getMinBlockX();
		int baseZ = chunk.getPos().getMinBlockZ();
		for (int index = scanSection; index < sections.length; index++) {
			if (System.nanoTime() >= deadlineNanos) {
				scanSection = index;
				return false;
			}
			LevelChunkSection section = sections[index];
			scanSection = index + 1;
			if (section == null || section.hasOnlyAir() || !section.maybeHas(matcher)) {
				continue;
			}
			int sectionY = minY + (index << 4);
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = section.getBlockState(x, y, z);
						if (state == null || !filter.contains(state.getBlock())) {
							continue;
						}
						int worldX = baseX + x;
						int worldY = sectionY + y;
						int worldZ = baseZ + z;
						AABB box = new AABB(worldX, worldY, worldZ,
								worldX + 1.0, worldY + 1.0, worldZ + 1.0).inflate(INFLATE);
						scratch.add(new Found(box, state.getBlock().getName().getString(), state.getBlock()));
					}
				}
			}
		}

		// Min Count is per block type inside this chunk, not per position: two
		// diamond ore blocks close together are two, not one flag each.
		Map<Block, Integer> counts = new HashMap<>();
		for (Found foundBlock : scratch) {
			counts.merge(foundBlock.block(), 1, Integer::sum);
		}
		List<AABB> boxes = new ArrayList<>(scratch.size());
		List<Found> kept = new ArrayList<>(scratch.size());
		int needed = minCount.get();
		for (Found foundBlock : scratch) {
			if (counts.get(foundBlock.block()) < needed) {
				continue;
			}
			boxes.add(foundBlock.box());
			kept.add(foundBlock);
		}
		replaceChunk(key, boxes, kept);
		rebuildRender();
		clearScan();
		return true;
	}

	private void replaceChunk(long key, List<AABB> boxes, List<Found> found) {
		if (boxes.isEmpty() || found.isEmpty()) {
			boxesByChunk.remove(key);
			unmarkFlag(key);
			rebuildRender();
			return;
		}
		boolean had = flags.containsKey(key);
		boxesByChunk.put(key, List.copyOf(boxes));
		markFlag(key, "found " + found.get(0).name(), boxes.size(), boxes);
		if (!had && chatMessage.value()) {
			notify.chat(Minecraft.getInstance(), "Block Notifier: " + boxes.size() + " x "
					+ found.get(0).name() + " in chunk " + Chunks.x(key) + ", " + Chunks.z(key));
		}
	}

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

	private void rebuildRender() {
		renderBoxes.clear();
		for (List<AABB> boxes : boxesByChunk.values()) {
			renderBoxes.addAll(boxes);
		}
	}

	private void clearScan() {
		scanning = false;
		scanKey = Long.MIN_VALUE;
		scanSection = 0;
		scratch.clear();
	}

	@Override
	public List<AABB> boxes() {
		return renderBoxes;
	}

	@Override
	public void drawFlags(DrawSink sink, double timeSec, double timeMs) {
		for (AABB box : boxesNearestFirst()) {
			double distance = boxDistance(box);
			if (!drawableFlag(distance) || !fullStyle(distance, 30)) {
				continue;
			}
			FlagStyles.cornerTicks(sink, (box.minX + box.maxX) * 0.5, box.maxY + 0.03,
					(box.minZ + box.maxZ) * 0.5, 0.5, colorFor(box, timeSec) & 0xFFFFFF, timeMs);
		}
	}

	@Override
	public String reliability() {
		return "can be hidden by anti-xray";
	}

	@Override
	protected void onChunkUnloaded(long key) {
		boxesByChunk.remove(key);
		// If the scanner was part-way through this chunk, its section index and
		// scratch list belong to a chunk that no longer exists. A reloaded chunk
		// may have a different height or contents, so start from the beginning.
		if (scanning && scanKey == key) {
			clearScan();
		}
		rebuildRender();
	}

	@Override
	public void resetSession() {
		super.resetSession();
		boxesByChunk.clear();
		renderBoxes.clear();
		scratch.clear();
		filter.clear();
		filterSignature = Integer.MIN_VALUE;
		appliedMinCount = -1;
		placedSeen.clear();
		lastPlacedScanTick = Long.MIN_VALUE;
		clearScan();
	}

	@Override
	public void loadConfig(JsonObject json) {
		super.loadConfig(json);
		if (json != null && json.has("blocks")) {
			blocks.set(ConfigValues.stringList(json, "blocks"));
		}
		filterSignature = Integer.MIN_VALUE;
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

	private static List<String> defaultBlocks() {
		return List.of(
				"minecraft:spawner",
				"minecraft:ender_chest",
				"minecraft:beacon",
				"minecraft:shulker_box",
				"minecraft:white_shulker_box",
				"minecraft:orange_shulker_box",
				"minecraft:magenta_shulker_box",
				"minecraft:light_blue_shulker_box",
				"minecraft:yellow_shulker_box",
				"minecraft:lime_shulker_box",
				"minecraft:pink_shulker_box",
				"minecraft:gray_shulker_box",
				"minecraft:light_gray_shulker_box",
				"minecraft:cyan_shulker_box",
				"minecraft:purple_shulker_box",
				"minecraft:blue_shulker_box",
				"minecraft:brown_shulker_box",
				"minecraft:green_shulker_box",
				"minecraft:red_shulker_box",
				"minecraft:black_shulker_box");
	}

	private record Found(AABB box, String name, Block block) {
	}
}
