package com.capy.client.module.modules.esp;

import com.capy.client.config.ConfigValues;
import com.capy.client.finder.core.Chunks;
import com.capy.client.finder.core.SeedDeepslate;
import com.capy.client.module.SliderSetting;
import com.capy.client.module.StringSetting;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
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
	private static final long SCAN_BUDGET_NANOS = 1_500_000L;
	private static final long RESCAN_INTERVAL_TICKS = 1200L;

	private final StringSetting worldSeed = setting(new StringSetting("world_seed", ""));
	private final SliderSetting minimum = setting(new SliderSetting("minimum", 4, 1, 32));
	private final ArrayDeque<Long> queue = new ArrayDeque<>();
	private final Set<Long> queued = new HashSet<>();
	private SeedDeepslate seed;
	private long appliedSeed = Long.MIN_VALUE;
	private long lastEnqueueTick = -1L;
	private boolean warnedNoSeed;

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
		if (!resolveSeed(mc)) {
			return;
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
			if (chunk == null) {
				continue;
			}
			int suspicious = scanChunk(chunk);
			if (suspicious >= minimum.get()) {
				int baseX = chunk.getPos().getMinBlockX();
				int baseZ = chunk.getPos().getMinBlockZ();
				markFlag(key, List.of(new AABB(baseX, 0.05, baseZ, baseX + 16.0, 8.0, baseZ + 16.0)));
			} else {
				unmarkFlag(key);
			}
		}
	}

	private boolean resolveSeed(Minecraft mc) {
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
		if (!found && mc.hasSingleplayerServer()) {
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

	private void enqueueLoaded() {
		int radius = Math.min(12, simulationDistance() + 4);
		var player = Minecraft.getInstance().player;
		if (player == null) {
			return;
		}
		int cx = player.blockPosition().getX() >> 4;
		int cz = player.blockPosition().getZ() >> 4;
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
		int suspicious = 0;
		int baseX = chunk.getPos().getMinBlockX();
		int baseZ = chunk.getPos().getMinBlockZ();
		int minY = chunk.getMinY();
		LevelChunkSection[] sections = chunk.getSections();
		for (int worldY = 1; worldY < 8; worldY++) {
			int index = (worldY - minY) >> 4;
			if (index < 0 || index >= sections.length) {
				continue;
			}
			LevelChunkSection section = sections[index];
			if (section == null) {
				continue;
			}
			int localY = (worldY - minY) & 15;
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					BlockState state = section.getBlockState(x, localY, z);
					if (state.is(Blocks.DEEPSLATE) && seed.suspicious(baseX + x, worldY, baseZ + z)) {
						suspicious++;
					}
				}
			}
		}
		return suspicious;
	}

	@Override
	public void resetSession() {
		super.resetSession();
		queue.clear();
		queued.clear();
		seed = null;
		appliedSeed = Long.MIN_VALUE;
		lastEnqueueTick = -1L;
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
