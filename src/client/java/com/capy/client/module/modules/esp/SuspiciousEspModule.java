package com.capy.client.module.modules.esp;

import com.capy.client.finder.core.Chunks;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.ColorSetting;
import com.capy.client.module.IntSetting;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

/**
 * Suspicious ESP: the things that are usually a player's mark on a world -
 * traders, villagers, llamas and pillagers on the entity side, and deepslate,
 * vines, amethyst and kelp on the block side.
 *
 * <p>Each category has its own switch and its own colour. The two deepslate
 * filters are separate because a natural-looking block is a weaker clue than
 * one whose axis betrays a hand placement. Deepslate is only read inside the
 * configured Y band; the other block categories are read everywhere.</p>
 */
public final class SuspiciousEspModule extends FinderModule {
	private final BooleanSetting wanderingTraders = describe(setting(new BooleanSetting("wandering_traders", true)),
			"Show wandering traders.");
	private final BooleanSetting villagers = describe(setting(new BooleanSetting("villagers", true)),
			"Show villagers.");
	private final BooleanSetting llamas = describe(setting(new BooleanSetting("llamas", true)),
			"Show trader llamas.");
	private final BooleanSetting pillagers = describe(setting(new BooleanSetting("pillagers", true)),
			"Show pillagers.");
	private final BooleanSetting deepslate = describe(setting(new BooleanSetting("deepslate", true)),
			"Show deepslate inside the configured Y band.");
	private final BooleanSetting rotatedDeepslate = describe(setting(new BooleanSetting("rotated_deepslate", true)),
			"Show deepslate pillars that are not standing upright.");
	private final BooleanSetting vines = describe(setting(new BooleanSetting("vines", true)),
			"Show vines.");
	private final BooleanSetting amethystClusters = describe(setting(new BooleanSetting("amethyst_clusters", true)),
			"Show amethyst clusters and buds.");
	private final BooleanSetting kelpChunks = describe(setting(new BooleanSetting("kelp_chunks", true)),
			"Show chunks with enough kelp to be a farm.");
	private final IntSetting deepslateMinY = describe(setting(new IntSetting("deepslate_min_y", 16, -64, 320, 1)),
			"Lowest Y that deepslate is read at.");
	private final IntSetting deepslateMaxY = describe(setting(new IntSetting("deepslate_max_y", 128, -64, 320, 1)),
			"Highest Y that deepslate is read at.");
	private final IntSetting kelpThreshold = describe(setting(new IntSetting("kelp_threshold", 10, 1, 50, 1)),
			"How much kelp a chunk needs to be marked.");
	private final BooleanSetting tracer = describe(setting(new BooleanSetting("tracers", false)),
			"Draw a fading line from the crosshair to every find.");
	private final ColorSetting traderColor = color("trader_color", 0x96FF0000, "Wandering trader colour.");
	private final ColorSetting villagerColor = color("villager_color", 0x9600FF00, "Villager colour.");
	private final ColorSetting llamaColor = color("llama_color", 0x96FFA500, "Trader llama colour.");
	private final ColorSetting deepslateColor = color("deepslate_color", 0x9600C8FF, "Deepslate colour.");
	private final ColorSetting rotatedColor = color("rotated_color", 0x96FF00FF, "Rotated deepslate colour.");
	private final ColorSetting vineColor = color("vine_color", 0x9600FF00, "Vine colour.");
	private final ColorSetting amethystColor = color("amethyst_color", 0x96B400FF, "Amethyst colour.");
	private final ColorSetting kelpColor = color("kelp_color", 0x9600FFFF, "Kelp chunk colour.");

	private final Map<Long, List<KindBox>> blockBoxes = new HashMap<>();
	private final List<KindBox> entityBoxes = new ArrayList<>();
	private final List<AABB> allBoxes = new ArrayList<>();
	private final Map<AABB, Integer> colors = new IdentityHashMap<>();
	private int appliedSignature = Integer.MIN_VALUE;
	private boolean scanning;
	private long scanKey;
	private int scanSection;
	private final List<KindBox> scanBoxes = new ArrayList<>();
	private int scanKelp;
	private int scanKelpY;

	public SuspiciousEspModule() {
		super("suspicious_esp");
	}

	private ColorSetting color(String key, int argb, String tip) {
		return describe(setting(new ColorSetting(key, argb)), tip);
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!beginTick(mc)) {
			return;
		}
		int signature = signature();
		if (signature != appliedSignature) {
			appliedSignature = signature;
			blockBoxes.clear();
			entityBoxes.clear();
			clearFlags();
			FinderScanner.get().queueAllLoaded();
		}
		refreshEntities(mc);
		rebuild();
	}

	@Override
	public boolean scan(ClientLevel level, LevelChunk chunk, long deadlineNanos) {
		this.level = level;
		long key = Chunks.key(chunk.getPos().x, chunk.getPos().z);
		if (!scanning || scanKey != key) {
			scanning = true;
			scanKey = key;
			scanSection = 0;
			scanBoxes.clear();
			scanKelp = 0;
			scanKelpY = Integer.MIN_VALUE;
		}
		int minBand = Math.min(deepslateMinY.get(), deepslateMaxY.get());
		int maxBand = Math.max(deepslateMinY.get(), deepslateMaxY.get());
		boolean wantDeepslate = deepslate.value() || rotatedDeepslate.value();
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
			if (section == null || section.hasOnlyAir()) {
				continue;
			}
			int sectionY = minY + (index << 4);
			for (int y = 0; y < 16; y++) {
				int worldY = sectionY + y;
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = section.getBlockState(x, y, z);
						if (state.isAir()) {
							continue;
						}
						if (wantDeepslate && worldY >= minBand && worldY <= maxBand
								&& state.is(Blocks.DEEPSLATE)) {
							boolean rotated = state.getValue(RotatedPillarBlock.AXIS) != Direction.Axis.Y;
							if (rotated ? rotatedDeepslate.value() : deepslate.value()) {
								addScanBox(baseX + x, worldY, baseZ + z,
										rotated ? rotatedColor.argb() : deepslateColor.argb());
							}
						}
						if (vines.value() && state.is(Blocks.VINE)) {
							addScanBox(baseX + x, worldY, baseZ + z, vineColor.argb());
						}
						if (amethystClusters.value() && isAmethyst(state)) {
							addScanBox(baseX + x, worldY, baseZ + z, amethystColor.argb());
						}
						if (kelpChunks.value() && (state.is(Blocks.KELP) || state.is(Blocks.KELP_PLANT))) {
							scanKelp++;
							if (scanKelpY == Integer.MIN_VALUE) {
								scanKelpY = worldY;
							}
						}
					}
				}
			}
		}
		if (kelpChunks.value() && scanKelp >= kelpThreshold.get()) {
			int plateY = scanKelpY == Integer.MIN_VALUE ? minY : scanKelpY;
			scanBoxes.add(new KindBox(new AABB(baseX, plateY, baseZ,
					baseX + 16.0, plateY + 1.0, baseZ + 16.0), kelpColor.argb()));
		}
		if (scanBoxes.isEmpty()) {
			blockBoxes.remove(key);
		} else {
			blockBoxes.put(key, List.copyOf(scanBoxes));
		}
		scanning = false;
		scanBoxes.clear();
		rebuild();
		return true;
	}

	private void addScanBox(int x, int y, int z, int argb) {
		if (scanBoxes.size() >= 64) {
			return;
		}
		scanBoxes.add(new KindBox(new AABB(x, y, z, x + 1.0, y + 1.0, z + 1.0).inflate(0.003), argb));
	}

	private static boolean isAmethyst(BlockState state) {
		return state.is(Blocks.AMETHYST_CLUSTER) || state.is(Blocks.SMALL_AMETHYST_BUD)
				|| state.is(Blocks.MEDIUM_AMETHYST_BUD) || state.is(Blocks.LARGE_AMETHYST_BUD);
	}

	private void refreshEntities(Minecraft mc) {
		entityBoxes.clear();
		if (mc == null || mc.level == null || mc.player == null) {
			return;
		}
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity == null || entity.isRemoved() || entity.isInvisible()) {
				continue;
			}
			if (entity.distanceToSqr(mc.player) > 128.0 * 128.0) {
				continue;
			}
			int argb = entityColor(entity.getType());
			if (argb == 0) {
				continue;
			}
			entityBoxes.add(new KindBox(entity.getBoundingBox().inflate(0.08), argb));
		}
	}

	private int entityColor(EntityType<?> type) {
		if (type == EntityType.WANDERING_TRADER) {
			return wanderingTraders.value() ? traderColor.argb() : 0;
		}
		if (type == EntityType.VILLAGER) {
			return villagers.value() ? villagerColor.argb() : 0;
		}
		if (type == EntityType.LLAMA || type == EntityType.TRADER_LLAMA) {
			return llamas.value() ? llamaColor.argb() : 0;
		}
		if (type == EntityType.PILLAGER) {
			return pillagers.value() ? pillagerColor().argb() : 0;
		}
		return 0;
	}

	private ColorSetting pillagerColor() {
		// The card names four entity colours; pillagers deliberately share the
		// trader hue family so the list stays the size the layout asked for.
		return traderColor;
	}

	private void rebuild() {
		allBoxes.clear();
		colors.clear();
		for (KindBox box : entityBoxes) {
			addBox(box);
		}
		for (List<KindBox> boxes : blockBoxes.values()) {
			for (KindBox box : boxes) {
				addBox(box);
			}
		}
	}

	private void addBox(KindBox box) {
		if (allBoxes.size() >= 512) {
			return;
		}
		allBoxes.add(box.box());
		colors.put(box.box(), box.argb());
	}

	/** Changing a kind or a threshold rescans, because old boxes belong to another filter. */
	private int signature() {
		int hash = 1;
		BooleanSetting[] kinds = {wanderingTraders, villagers, llamas, pillagers, deepslate,
				rotatedDeepslate, vines, amethystClusters, kelpChunks};
		for (BooleanSetting kind : kinds) {
			hash = hash * 31 + (kind.value() ? 1 : 0);
		}
		hash = hash * 31 + deepslateMinY.get();
		hash = hash * 31 + deepslateMaxY.get();
		hash = hash * 31 + kelpThreshold.get();
		return hash;
	}

	@Override
	public List<AABB> boxes() {
		return allBoxes;
	}

	@Override
	public boolean tracers() {
		return tracer.value();
	}

	@Override
	public boolean tracerFade() {
		return tracer.value();
	}

	@Override
	public int colorFor(AABB box, double timeSec) {
		Integer argb = colors.get(box);
		return argb == null ? 0xFFFFFFFF : argb;
	}

	@Override
	public String reliability() {
		return "entities and placed blocks";
	}

	@Override
	protected void onChunkUnloaded(long key) {
		blockBoxes.remove(key);
		if (scanning && scanKey == key) {
			clearScan();
		}
		rebuild();
	}

	private void clearScan() {
		scanning = false;
		scanKey = 0L;
		scanSection = 0;
		scanBoxes.clear();
	}

	@Override
	public void resetSession() {
		super.resetSession();
		blockBoxes.clear();
		entityBoxes.clear();
		allBoxes.clear();
		colors.clear();
		appliedSignature = Integer.MIN_VALUE;
		scanKelp = 0;
		scanKelpY = Integer.MIN_VALUE;
		clearScan();
	}

	private record KindBox(AABB box, int argb) {
	}
}
