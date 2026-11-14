package com.capy.client.module.modules.esp;

import com.capy.client.config.ConfigValues;
import com.capy.client.finder.core.ChunkStyles;
import com.capy.client.finder.core.Chunks;
import com.capy.client.finder.core.DrawSink;
import com.capy.client.finder.core.SeedDeepslate;
import com.capy.client.module.SliderSetting;
import com.capy.client.module.StringSetting;
import com.google.gson.JsonObject;
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
 * Read-only deepslate finder. It sends no packets and never changes the world.
 *
 * <p>The overworld's natural deepslate near bedrock follows a seeded gradient.
 * Deepslate in that band which the gradient says should not be natural is
 * evidence of player building. The world seed is not sent by a multiplayer
 * server, so the finder uses the integrated server's seed in singleplayer and
 * accepts an optional seed in the module settings; without either it stays
 * idle and says so once in the log. This is an assumption forced by the
 * protocol, not a guess about the world.</p>
 */
public final class DeepslateFinderModule extends FinderModule {
	private static final Logger LOGGER = LoggerFactory.getLogger(DeepslateFinderModule.class);

	private final StringSetting worldSeed = setting(new StringSetting("world_seed", ""));
	private final SliderSetting minimum = setting(new SliderSetting("minimum", 4, 1, 32));
	private SeedDeepslate seed;
	private long appliedSeed = Long.MIN_VALUE;
	private boolean warnedNoSeed;
	private boolean scanning;
	private long scanKey;
	private int scanSection;
	private int scanSuspicious;

	public DeepslateFinderModule() {
		super("deepslate_finder", 0xFF6FA8DC);
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
		if (resolveSeed() && appliedSeed != before) {
			FinderScanner.get().queueAllLoaded();
		}
	}

	@Override
	public boolean scan(ClientLevel level, LevelChunk chunk, long deadlineNanos) {
		this.level = level;
		if (level.dimension() != Level.OVERWORLD) {
			return true;
		}
		if (!resolveSeed()) {
			return true;
		}
		long key = Chunks.key(chunk.getPos().x, chunk.getPos().z);
		int minY = chunk.getMinY();
		int firstSection = (1 - minY) >> 4;
		if (!scanning || scanKey != key) {
			scanning = true;
			scanKey = key;
			scanSection = firstSection;
			scanSuspicious = 0;
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
						if (state.is(Blocks.DEEPSLATE) && seed.suspicious(baseX + x, worldY, baseZ + z)) {
							scanSuspicious++;
						}
					}
				}
			}
		}
		if (scanSuspicious >= minimum.get()) {
			markFlag(key, "suspicious deepslate", scanSuspicious, List.of(new AABB(baseX, 0.05, baseZ,
					baseX + 16.0, 8.0, baseZ + 16.0)));
		} else {
			unmarkFlag(key);
		}
		clearScan();
		return true;
	}

	private boolean resolveSeed() {
		String text = worldSeed.value();
		long source = 0L;
		boolean found = false;
		if (!text.isBlank()) {
			try {
				source = Long.parseLong(text);
				found = true;
			} catch (NumberFormatException e) {
				if (!warnedNoSeed) {
					warnedNoSeed = true;
					LOGGER.warn("Capy: deepslate finder seed '{}' is not a signed integer; the finder is idle.", text);
				}
			}
		}
		Minecraft mc = Minecraft.getInstance();
		if (!found && mc != null && mc.hasSingleplayerServer()) {
			var server = mc.getSingleplayerServer();
			if (server != null) {
				source = server.overworld().getSeed();
				found = true;
			}
		}
		if (!found) {
			if (!warnedNoSeed) {
				warnedNoSeed = true;
				LOGGER.info("Capy: deepslate finder needs a seed on a multiplayer server; set world_seed to enable it.");
			}
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
		if (flags.isEmpty()) {
			return;
		}
		Set<Long> chunks = new HashSet<>(flags.keySet());
		int argb = colorArgb();
		int alpha = argb >>> 24;
		ChunkStyles.fillContour(sink, chunks, 8.05, argb & 0xFFFFFF,
				Math.max(28, Math.round(alpha * 0.16f)), Math.min(255, Math.max(180, alpha)), timeSec);
	}

	@Override
	protected String statusHint() {
		return seed == null ? "waiting for a world seed" : "no seeded deepslate cluster";
	}

	private void clearScan() {
		scanning = false;
		scanKey = 0L;
		scanSection = 0;
		scanSuspicious = 0;
	}

	@Override
	public void resetSession() {
		super.resetSession();
		seed = null;
		appliedSeed = Long.MIN_VALUE;
		clearScan();
	}

	@Override
	public void loadConfig(JsonObject json) {
		super.loadConfig(json);
		worldSeed.set(ConfigValues.stringValue(json, "world_seed", ""));
		minimum.set(ConfigValues.intValue(json, "minimum", 4));
		seed = null;
		appliedSeed = Long.MIN_VALUE;
	}

	@Override
	public void saveConfig(JsonObject json) {
		super.saveConfig(json);
		json.addProperty("world_seed", worldSeed.value());
		json.addProperty("minimum", minimum.get());
	}
}
