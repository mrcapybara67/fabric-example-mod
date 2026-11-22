package com.capy.client.module.modules.esp;

import com.capy.client.config.ConfigValues;
import com.capy.client.finder.core.ChunkStyles;
import com.capy.client.finder.core.Chunks;
import com.capy.client.finder.core.DrawSink;
import com.capy.client.finder.core.FlagStyles;
import com.capy.client.finder.core.SeedDeepslate;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.IntSetting;
import com.capy.client.module.SliderSetting;
import com.capy.client.module.StringSetting;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seed Chunk Finder: read-only deepslate finder.
 *
 * <p>The overworld's natural deepslate near bedrock follows a seeded gradient.
 * Deepslate in that band which the gradient says should not be natural is
 * evidence of player building. The blocks must also span several columns, so
 * a single placed pillar is not turned into a base flag. The world seed is not
 * sent by a multiplayer server, so the finder uses the Server Seed setting,
 * then an older saved world_seed, then the integrated server's seed in
 * singleplayer. Without any seed it stays idle and says so once in the log.</p>
 *
 * <p>Render Y is where the flag plate is drawn: a base at Y 40 can be reviewed
 * at eye level instead of at the bottom of the world.</p>
 */
public final class DeepslateFinderModule extends FinderModule {
	private static final Logger LOGGER = LoggerFactory.getLogger(DeepslateFinderModule.class);

	private final StringSetting serverSeed = describe(setting(new StringSetting("server_seed", "")),
			"The server's world seed, used to judge natural deepslate.");
	private final BooleanSetting notifications = describe(setting(new BooleanSetting("notify", false)),
			"Show a short toast when a new suspect chunk is found.");
	private final IntSetting renderY = describe(setting(new IntSetting("render_y", 63, -64, 320, 1)),
			"The Y level the flag plates are drawn at.");
	private final SliderSetting minimum = describe(setting(new SliderSetting("minimum", 4, 1, 32)),
			"How many suspicious deepslate blocks a chunk needs to be flagged.");
	private final IntSetting minimumColumns = describe(setting(new IntSetting("minimum_columns", 2, 1, 8, 1)),
			"How many separate columns the suspicious deepslate must span.");
	private String legacySeed = "";
	private SeedDeepslate seed;
	private long appliedSeed = Long.MIN_VALUE;
	private int appliedMinimum = 4;
	private int appliedColumns = 2;
	private int appliedRenderY = 63;
	private boolean warnedNoSeed;
	private boolean scanning;
	private long scanKey;
	private int scanSection;
	private int scanSuspicious;
	private final BitSet scanColumns = new BitSet(256);

	public DeepslateFinderModule() {
		super("deepslate_finder", 0x9600FF00);
		useShape("both");
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!beginTick(mc)) {
			return;
		}
		if (level.dimension() != Level.OVERWORLD) {
			return;
		}
		long before = appliedSeed;
		if (!resolveSeed()) {
			if (before != Long.MIN_VALUE || !flags.isEmpty()) {
				resetSeedView();
			}
			return;
		}
		if (appliedSeed != before) {
			resetSeedView();
			FinderScanner.get().queueAllLoaded();
		}
		int minimumValue = minimum.get();
		if (minimumValue != appliedMinimum) {
			appliedMinimum = minimumValue;
			resetSeedView();
			FinderScanner.get().queueAllLoaded();
		}
		int columnsValue = minimumColumns.get();
		if (columnsValue != appliedColumns) {
			appliedColumns = columnsValue;
			resetSeedView();
			FinderScanner.get().queueAllLoaded();
		}
		int renderYValue = renderY.get();
		if (renderYValue != appliedRenderY) {
			// A rescan would lose the current flags; only their plate height
			// has to move, so rebuild those boxes in place.
			appliedRenderY = renderYValue;
			refreshPlateBoxes(renderYValue);
		}
		refreshTerrain(flags.keySet());
	}

	@Override
	public boolean scan(ClientLevel level, LevelChunk chunk, long deadlineNanos) {
		this.level = level;
		if (level.dimension() != Level.OVERWORLD) {
			return true;
		}
		long beforeSeed = appliedSeed;
		if (!resolveSeed()) {
			resetSeedView();
			return true;
		}
		if (appliedSeed != beforeSeed) {
			resetSeedView();
			return true;
		}
		long key = Chunks.key(chunk.getPos().x, chunk.getPos().z);
		boolean hadFlag = flags.containsKey(key);
		int minY = chunk.getMinY();
		int firstSection = (1 - minY) >> 4;
		if (!scanning || scanKey != key) {
			scanning = true;
			scanKey = key;
			scanSection = firstSection;
			scanSuspicious = 0;
			scanColumns.clear();
		}

		int baseX = chunk.getPos().getMinBlockX();
		int baseZ = chunk.getPos().getMinBlockZ();
		int lastSection = (7 - minY) >> 4;
		LevelChunkSection[] sections = chunk.getSections();
		for (int index = scanSection; index <= lastSection; index++) {
			if (System.nanoTime() >= deadlineNanos) {
				scanSection = index;
				return false;
			}
			scanSection = index + 1;
			if (index < 0 || index >= sections.length) {
				continue;
			}
			LevelChunkSection section = sections[index];
			if (section == null) {
				continue;
			}
			int sectionY = minY + (index << 4);
			int fromY = Math.max(1, sectionY);
			int toY = Math.min(7, sectionY + 15);
			for (int worldY = fromY; worldY <= toY; worldY++) {
				int localY = (worldY - minY) & 15;
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = section.getBlockState(x, localY, z);
						if (state != null && state.is(Blocks.DEEPSLATE)
								&& seed.suspicious(baseX + x, worldY, baseZ + z)) {
							scanSuspicious++;
							scanColumns.set((x << 4) | z);
						}
					}
				}
			}
		}
		if (enoughEvidence(scanSuspicious, scanColumns.cardinality(), minimum.get(), minimumColumns.get())) {
			int plateY = renderY.get();
			markFlag(key, scanSuspicious + " suspicious deepslate in " + scanColumns.cardinality()
					+ " columns", scanSuspicious,
					List.of(new AABB(baseX, plateY, baseZ, baseX + 16.0, plateY + 0.1, baseZ + 16.0)));
			if (!hadFlag && notifications.value()) {
				notify.toast(Minecraft.getInstance(), "Seed chunk finder",
						scanSuspicious + " suspicious deepslate near " + (Chunks.x(key) * 16 + 8)
								+ ", " + (Chunks.z(key) * 16 + 8));
			}
		} else {
			unmarkFlag(key);
		}
		clearScan();
		return true;
	}

	/** A single pillar of placed deepslate is not a base; spread-out blocks are. */
	static boolean enoughEvidence(int blocks, int columns, int minimumBlocks, int minimumSpans) {
		return blocks >= Math.max(1, minimumBlocks) && columns >= Math.max(1, minimumSpans);
	}

	private boolean resolveSeed() {
		String text = serverSeed.value();
		if (text.isBlank()) {
			text = legacySeed;
		}
		long source = 0L;
		boolean found = false;
		if (!text.isBlank()) {
			try {
				source = Long.parseLong(text.trim());
				found = true;
			} catch (NumberFormatException e) {
				if (!warnedNoSeed) {
					warnedNoSeed = true;
					LOGGER.warn("Capy: seed chunk finder seed '{}' is not a signed integer; the finder is idle.", text);
				}
				seed = null;
				appliedSeed = Long.MIN_VALUE;
				return false;
			}
		}
		Minecraft mc = Minecraft.getInstance();
		if (!found && text.isBlank() && mc != null && mc.hasSingleplayerServer()) {
			var server = mc.getSingleplayerServer();
			if (server != null) {
				source = server.overworld().getSeed();
				found = true;
			}
		}
		if (!found) {
			if (!warnedNoSeed) {
				warnedNoSeed = true;
				LOGGER.info("Capy: seed chunk finder needs a seed on a multiplayer server; set Server Seed to enable it.");
			}
			seed = null;
			appliedSeed = Long.MIN_VALUE;
			return false;
		}
		if (seed == null || appliedSeed != source) {
			seed = new SeedDeepslate(source);
			appliedSeed = source;
		}
		return true;
	}

	@Override
	public void drawFlags(DrawSink sink, double timeSec, double timeMs) {
		drawSharedChunkFlags(sink, timeSec, timeMs);
	}

	@Override
	protected void onChunkUnloaded(long key) {
		if (scanning && scanKey == key) {
			clearScan();
		}
	}

	private void resetSeedView() {
		clearFlags();
		clearScan();
	}

	/** Moves the existing flag plates to a newly chosen Render Y. */
	private void refreshPlateBoxes(int plateY) {
		beginBulk();
		for (long key : new ArrayList<>(flags.keySet())) {
			int baseX = Chunks.x(key) * 16;
			int baseZ = Chunks.z(key) * 16;
			boxesByFlag.put(key, List.of(new AABB(baseX, plateY, baseZ,
					baseX + 16.0, plateY + 0.1, baseZ + 16.0)));
		}
		endBulk();
	}

	private void clearScan() {
		scanning = false;
		scanKey = 0L;
		scanSection = 0;
		scanSuspicious = 0;
		scanColumns.clear();
	}

	@Override
	public void resetSession() {
		super.resetSession();
		seed = null;
		appliedSeed = Long.MIN_VALUE;
		appliedMinimum = minimum.get();
		appliedColumns = minimumColumns.get();
		appliedRenderY = renderY.get();
		clearScan();
	}

	@Override
	public void loadConfig(JsonObject json) {
		super.loadConfig(json);
		// world_seed is the old key; it is read but never shown or rewritten.
		legacySeed = ConfigValues.stringValue(json, "world_seed", "");
		serverSeed.set(ConfigValues.stringValue(json, "server_seed", ""));
		minimum.set(ConfigValues.intValue(json, "minimum", 4));
		minimumColumns.set(ConfigValues.intValue(json, "minimum_columns", 2));
		renderY.set(ConfigValues.intValue(json, "render_y", 63));
		notifications.set(ConfigValues.boolValue(json, "notify", false));
		seed = null;
		appliedSeed = Long.MIN_VALUE;
		appliedMinimum = minimum.get();
		appliedColumns = minimumColumns.get();
		appliedRenderY = renderY.get();
	}

	@Override
	public void saveConfig(JsonObject json) {
		super.saveConfig(json);
		json.addProperty("server_seed", serverSeed.value());
		json.addProperty("minimum", minimum.get());
		json.addProperty("minimum_columns", minimumColumns.get());
		json.addProperty("render_y", renderY.get());
		json.addProperty("notify", notifications.value());
	}
}
