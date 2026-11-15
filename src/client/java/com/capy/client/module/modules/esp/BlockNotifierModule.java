package com.capy.client.module.modules.esp;

import com.capy.client.config.ConfigValues;
import com.capy.client.finder.core.Chunks;
import com.capy.client.module.BlockListSetting;
import com.capy.client.util.Guard;
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
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

/**
 * Block Notifier: finds the blocks on the user's list and announces each new
 * one with a toast.
 *
 * <p>The palette check happens before any cell is read, so a chunk of stone
 * costs one predicate pass per section. A found block gets an outlined box and
 * a toast whose body is exactly {@code found <block name>}. A block already
 * announced in a chunk is not announced again on a rescan.</p>
 */
public final class BlockNotifierModule extends FinderModule {
	private static final double INFLATE = 0.003;

	private final BlockListSetting blocks = setting(new BlockListSetting("blocks"));
	private final Set<Block> filter = new HashSet<>();
	private final Predicate<BlockState> matcher = state -> filter.contains(state.getBlock());
	private final Map<Long, List<AABB>> boxesByChunk = new HashMap<>();
	private final Map<Long, Set<Long>> announcedByChunk = new HashMap<>();
	private final List<AABB> renderBoxes = new ArrayList<>();
	private final List<Found> scratch = new ArrayList<>();
	private boolean scanning;
	private long scanKey = Long.MIN_VALUE;
	private int scanSection;
	private int filterSignature = Integer.MIN_VALUE;
	private int foundCount;

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
			announcedByChunk.clear();
			scratch.clear();
			scanning = false;
			scanKey = Long.MIN_VALUE;
			scanSection = 0;
			rebuildRender();
			FinderScanner.get().queueAllLoaded();
		}
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
						scratch.add(new Found(Chunks.block(worldX, worldY, worldZ), box,
								state.getBlock().getName().getString()));
					}
				}
			}
		}

		List<AABB> boxes = new ArrayList<>(scratch.size());
		Set<Long> found = new HashSet<>();
		for (Found foundBlock : scratch) {
			boxes.add(foundBlock.box());
			found.add(foundBlock.key());
		}
		replaceChunk(key, boxes, new ArrayList<>(scratch));
		if (!boxes.isEmpty()) {
			Set<Long> already = announcedByChunk.computeIfAbsent(key, ignored -> new HashSet<>());
			already.retainAll(found);
			for (Found foundBlock : scratch) {
				if (already.add(foundBlock.key())) {
					toast(foundBlock.name());
				}
			}
		}
		rebuildRender();
		clearScan();
		return true;
	}

	private void replaceChunk(long key, List<AABB> boxes, List<Found> found) {
		if (boxes.isEmpty()) {
			boxesByChunk.remove(key);
			announcedByChunk.remove(key);
			unmarkFlag(key);
			rebuildRender();
			return;
		}
		boxesByChunk.put(key, List.copyOf(boxes));
		// The notifier emits exactly one toast per found block below. Mark the
		// chunk as already announced first so FinderModule.markFlag does not add
		// its generic one-toast-per-chunk on top.
		announced.add(key);
		markFlag(key, "found " + found.get(0).name(), boxes.size(), boxes);
	}

	private void toast(String blockName) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.getToastManager() == null) {
			return;
		}
		Guard.run("block notifier toast", () -> SystemToast.add(mc.getToastManager(),
				SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
				Component.translatable(translationKey()), Component.literal("found " + blockName)));
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
		int count = 0;
		for (List<AABB> boxes : boxesByChunk.values()) {
			renderBoxes.addAll(boxes);
			count += boxes.size();
		}
		foundCount = count;
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
	public String statusLine() {
		return "scanned " + FinderScanner.get().scannedCount(this) + ", found " + foundCount;
	}

	@Override
	protected void onChunkUnloaded(long key) {
		boxesByChunk.remove(key);
		announcedByChunk.remove(key);
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
		announcedByChunk.clear();
		renderBoxes.clear();
		scratch.clear();
		filter.clear();
		filterSignature = Integer.MIN_VALUE;
		foundCount = 0;
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

	private record Found(long key, AABB box, String name) {
	}
}
