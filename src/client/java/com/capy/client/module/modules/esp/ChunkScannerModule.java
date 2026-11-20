package com.capy.client.module.modules.esp;

import com.capy.client.finder.core.ChunkStyles;
import com.capy.client.finder.core.Chunks;
import com.capy.client.finder.core.DrawSink;
import com.capy.client.finder.core.FlagStyles;
import com.capy.client.finder.core.SeedDeepslate;
import com.capy.client.finder.core.ShaftFinder;
import com.capy.client.finder.core.TerrainPlate;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.IntSetting;
import com.capy.client.module.StringSetting;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Chunk Scanner: flags chunks whose tallest shaft reaches the sensitivity
 * height inside the lowest 33-block band.
 *
 * <p>Sensitivity is the minimum stack height (2 to 5 blocks), not a vague
 * "strength": raising it removes the short dips, lowering it finds shallow
 * tunnels. Spread Radius decides how far two flagged chunks may be apart and
 * still be treated as one tunnel; only the tallest chunk of each group is kept,
 * so one long tunnel becomes one flag instead of a wall of them. A flag older
 * than sixty seconds is drawn dimmer.</p>
 */
public final class ChunkScannerModule extends ShaftScanModule {
	/** Y 0..32 is 33 blocks; elsewhere it starts at Y=0, or a higher world floor. */
	private static final int BAND_HEIGHT = 33;
	private static final long DIM_AFTER_MS = 60_000L;
	private static final double DIM_FACTOR = 0.55;

	private final BooleanSetting advanced = describe(setting(new BooleanSetting("advanced_scanner", false)),
			"Also judge candidate chunks with the seeded deepslate rule.");
	private final StringSetting seedText = describe(setting(new StringSetting("seed", "")),
			"The world seed used by the advanced deepslate rule; blank disables it.");
	private final IntSetting sensitivity = describe(setting(new IntSetting("sensitivity", 2, 2, 5, 1)),
			"Minimum shaft stack height that counts as a find.");
	private final IntSetting spreadRadius = describe(setting(new IntSetting("spread_radius", 1, 0, 16, 1)),
			"Group flagged chunks this many chunks apart and keep only the strongest.");
	private final BooleanSetting tracer = describe(setting(new BooleanSetting("tracers", true)),
			"Draw a fading line from the crosshair to every flagged chunk.");
	private final BooleanSetting sound = describe(setting(new BooleanSetting("sound", true)),
			"Play one short chime when a new chunk is flagged.");

	private final Map<Long, Long> flaggedAt = new HashMap<>();
	private final Map<Long, Integer> bottomY = new HashMap<>();
	private final Map<Long, Integer> topY = new HashMap<>();
	private final Set<Long> fresh = new HashSet<>();
	private final Set<Long> old = new HashSet<>();
	private final Set<Long> oneChunk = new HashSet<>(1);

	private SeedDeepslate seedRule;
	private long appliedSeed = Long.MIN_VALUE;
	private int appliedSpread = -1;
	private boolean appliedAdvanced;
	private long lastChimeMs = Long.MIN_VALUE / 2;

	public ChunkScannerModule() {
		super("chunk_scanner", 0xFFFFF2B0);
	}

	@Override
	protected int scanDepth() {
		return sensitivity.get();
	}

	@Override
	public boolean fills() {
		return false;
	}

	@Override
	public boolean outlines() {
		// The chunk plate is drawn by this module's own styles; the boxes are
		// kept only so the tracer has a target to point at.
		return false;
	}

	@Override
	public boolean tracers() {
		// The shared beacon look owns the tracer now; the old box tracer would
		// double the lines.
		return false;
	}

	@Override
	protected boolean flagTracers() {
		return tracer.value();
	}

	@Override
	public boolean tracerFade() {
		return tracer.value();
	}

	@Override
	public void onTick(Minecraft mc) {
		super.onTick(mc);
		if (level == null) {
			return;
		}
		if (appliedSpread != spreadRadius.get() || appliedAdvanced != advanced.value() || !seedMatches()) {
			appliedSpread = spreadRadius.get();
			appliedAdvanced = advanced.value();
			SeedDeepslate before = seedRule;
			seedRule = parseSeed();
			if (before != seedRule) {
				appliedSeed = seedRule == null ? Long.MIN_VALUE : appliedSeed;
			}
			shaftsChanged();
		}
	}

	private boolean seedMatches() {
		long parsed = parseSeedValue();
		return parsed == appliedSeed;
	}

	private long parseSeedValue() {
		String text = seedText.value();
		if (!advanced.value() || text.isBlank()) {
			return Long.MIN_VALUE;
		}
		try {
			return Long.parseLong(text.trim());
		} catch (NumberFormatException ignored) {
			return Long.MIN_VALUE;
		}
	}

	private SeedDeepslate parseSeed() {
		long value = parseSeedValue();
		if (value == Long.MIN_VALUE) {
			seedRule = null;
			appliedSeed = Long.MIN_VALUE;
			return null;
		}
		if (seedRule == null || appliedSeed != value) {
			seedRule = new SeedDeepslate(value);
			appliedSeed = value;
		}
		return seedRule;
	}

	@Override
	protected void shaftsChanged() {
		int minHeight = sensitivity.get();
		int bandMin = bandMin();
		int bandTop = bandMin + BAND_HEIGHT;
		Map<Long, int[]> tallest = new HashMap<>();
		for (List<ShaftFinder.Shaft> shafts : shaftLists()) {
			for (ShaftFinder.Shaft shaft : shafts) {
				int min = Math.max(shaft.minY(), bandMin);
				int max = Math.min(shaft.maxY(), bandTop);
				int height = max - min;
				if (height < minHeight) {
					continue;
				}
				long key = Chunks.key(shaft.x() >> 4, shaft.z() >> 4);
				int[] best = tallest.get(key);
				if (best == null || height > best[0]) {
					tallest.put(key, new int[]{height, min, max});
				}
			}
		}

		Map<Long, Integer> heights = new HashMap<>(tallest.size());
		tallest.forEach((key, info) -> heights.put(key, info[0]));
		Map<Long, Integer> winners = keepTallest(heights, spreadRadius.get());
		Map<Long, Integer> extras = new HashMap<>();
		if (advanced.value() && parseSeed() != null) {
			for (long key : new ArrayList<>(winners.keySet())) {
				int suspicious = countSeedDeepslate(key, seedRule);
				if (suspicious > 0) {
					extras.put(key, suspicious);
					winners.merge(key, suspicious, Math::max);
				}
			}
		}

		Set<Long> before = new HashSet<>(flags.keySet());
		long now = System.currentTimeMillis();
		flaggedAt.keySet().removeIf(key -> !winners.containsKey(key));
		bottomY.keySet().removeIf(key -> !winners.containsKey(key));
		topY.keySet().removeIf(key -> !winners.containsKey(key));
		beginBulk();
		clearFlags();
		boolean announced = false;
		for (Map.Entry<Long, Integer> entry : winners.entrySet()) {
			long key = entry.getKey();
			int[] info = tallest.get(key);
			flaggedAt.putIfAbsent(key, now);
			if (info != null) {
				bottomY.put(key, info[1]);
				topY.put(key, info[2]);
			}
			String reason = "shaft " + entry.getValue() + " blocks tall, Y "
					+ (info == null ? 0 : info[1]) + " to " + (info == null ? entry.getValue() : info[2]);
			Integer seedHits = extras.get(key);
			if (seedHits != null) {
				reason += ", seed deepslate " + seedHits;
			}
			markFlag(key, reason, entry.getValue(), List.of(surfaceBox(key)));
			if (!announced && sound.value() && !before.contains(key)) {
				announced = true;
				playChime();
			}
		}
		endBulk();
		retainTerrain(winners.keySet());
		refreshTerrain(winners.keySet());
	}

	/** Counts seed-suspicious deepslate in the natural deepslate band. */
	private int countSeedDeepslate(long key, SeedDeepslate rule) {
		if (level == null || rule == null) {
			return 0;
		}
		LevelChunk chunk = level.getChunkSource().getChunkNow(Chunks.x(key), Chunks.z(key));
		if (chunk == null) {
			return 0;
		}
		int minY = chunk.getMinY();
		int baseX = chunk.getPos().getMinBlockX();
		int baseZ = chunk.getPos().getMinBlockZ();
		LevelChunkSection[] sections = chunk.getSections();
		int first = (1 - minY) >> 4;
		int last = (7 - minY) >> 4;
		int count = 0;
		for (int index = Math.max(0, first); index <= last && index < sections.length; index++) {
			LevelChunkSection section = sections[index];
			if (section == null) {
				continue;
			}
			int sectionY = minY + (index << 4);
			int from = Math.max(1, sectionY);
			int to = Math.min(7, sectionY + 15);
			for (int y = from; y <= to; y++) {
				int localY = (y - minY) & 15;
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						if (section.getBlockState(x, localY, z).is(Blocks.DEEPSLATE)
								&& rule.suspicious(baseX + x, y, baseZ + z)) {
							count++;
						}
					}
				}
			}
		}
		return count;
	}

	private void playChime() {
		long now = System.currentTimeMillis();
		if (now - lastChimeMs < 1000L) {
			return;
		}
		lastChimeMs = now;
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.getSoundManager() == null) {
			return;
		}
		try {
			mc.getSoundManager().play(SimpleSoundInstance.forUI(
					SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.35f, 0.35f));
		} catch (RuntimeException ignored) {
			// A missing sound must never lose the find.
		}
	}

	private int bandMin() {
		if (level == null || level.dimension() == Level.OVERWORLD) {
			return 0;
		}
		return Math.max(0, level.getMinY());
	}

	/**
	 * Keeps one chunk per connected patch: the tallest shaft wins, with the
	 * chunk nearest the patch centroid breaking a tie. A spread of 0 keeps one
	 * flag per chunk; a larger spread merges a wider neighbourhood.
	 */
	static Map<Long, Integer> keepTallest(Map<Long, Integer> heights, int spread) {
		int radius = Math.max(0, spread);
		Map<Long, Integer> winners = new HashMap<>();
		Set<Long> seen = new HashSet<>();
		ArrayDeque<Long> queue = new ArrayDeque<>();
		for (long start : heights.keySet()) {
			if (!seen.add(start)) {
				continue;
			}
			List<Long> members = new ArrayList<>();
			queue.add(start);
			double sumX = 0;
			double sumZ = 0;
			while (!queue.isEmpty()) {
				long key = queue.poll();
				members.add(key);
				sumX += Chunks.x(key);
				sumZ += Chunks.z(key);
				for (int dx = -radius; dx <= radius; dx++) {
					for (int dz = -radius; dz <= radius; dz++) {
						if (dx == 0 && dz == 0) {
							continue;
						}
						long neighbour = Chunks.key(Chunks.x(key) + dx, Chunks.z(key) + dz);
						if (heights.containsKey(neighbour) && seen.add(neighbour)) {
							queue.add(neighbour);
						}
					}
				}
			}

			int bestHeight = 0;
			for (long key : members) {
				bestHeight = Math.max(bestHeight, heights.get(key));
			}
			double centreX = sumX / members.size();
			double centreZ = sumZ / members.size();
			long best = members.get(0);
			double bestDistance = Double.MAX_VALUE;
			for (long key : members) {
				if (heights.get(key) != bestHeight) {
					continue;
				}
				double dx = Chunks.x(key) - centreX;
				double dz = Chunks.z(key) - centreZ;
				double distance = dx * dx + dz * dz;
				if (distance < bestDistance || (distance == bestDistance && key < best)) {
					bestDistance = distance;
					best = key;
				}
			}
			winners.put(best, bestHeight);
		}
		return winners;
	}

	@Override
	public void drawFlags(DrawSink sink, double timeSec, double timeMs) {
		drawSharedChunkFlags(sink, timeSec, timeMs);
	}

	@Override
	protected int sharedFlagColor(long key, double timeSec) {
		int base = staleColor(key, colorArgb(timeSec));
		long since = flaggedAt.getOrDefault(key, System.currentTimeMillis());
		if (System.currentTimeMillis() - since < DIM_AFTER_MS) {
			return base;
		}
		return ChunkStyles.argb(base >>> 24, dim(base & 0xFFFFFF));
	}

	private void drawGroup(DrawSink sink, Set<Long> keys, int rgb, int alpha, int fill,
			int edge, double timeSec, double timeMs) {
		List<Long> ordered = new ArrayList<>(keys);
		ordered.sort(java.util.Comparator.comparingDouble(this::flagDistance));
		for (long key : ordered) {
			int[] heights = cachedTerrain(key);
			double distance = flagDistance(key);
			if (heights == null || !drawableFlag(distance)) {
				continue;
			}
			int keyRgb = isStale(key) ? dimRgb(rgb) : rgb;
			int keyAlpha = isStale(key) ? Math.max(24, alpha * 45 / 100) : alpha;
			int keyFill = Math.max(18, Math.round(keyAlpha * 0.40f));
			int keyEdge = Math.min(255, Math.max(150, keyAlpha));
			double average = TerrainPlate.average(heights);
			oneChunk.clear();
			oneChunk.add(key);
			ChunkStyles.fillContour(sink, oneChunk, average + 0.05, keyRgb, 0, keyEdge, timeSec);
			TerrainPlate.emit(sink, Chunks.x(key), Chunks.z(key), heights,
					ChunkStyles.argb(keyFill, keyRgb), 0.05);
			Integer shaftTop = topY.get(key);
			Integer shaftBottom = bottomY.get(key);
			if (shaftTop != null && shaftTop < average) {
				ChunkStyles.beam(sink, Chunks.x(key) * 16.0 + 8, Chunks.z(key) * 16.0 + 8,
						average + 0.05, shaftTop, ChunkStyles.argb(Math.min(180, alpha), rgb));
			}
			if (shaftTop != null && shaftBottom != null && fullStyle(distance, 130)) {
				FlagStyles.depthGauge(sink, Chunks.x(key) * 16.0 + 8, Chunks.z(key) * 16.0 + 8,
						shaftTop, shaftBottom, rgb, timeMs);
			}
		}
	}

	@Override
	protected void onChunkUnloaded(long key) {
		flaggedAt.remove(key);
		bottomY.remove(key);
		topY.remove(key);
		dropTerrain(key);
		super.onChunkUnloaded(key);
	}

	private static int dim(int rgb) {
		int red = Math.round(((rgb >>> 16) & 0xFF) * (float) DIM_FACTOR);
		int green = Math.round(((rgb >>> 8) & 0xFF) * (float) DIM_FACTOR);
		int blue = Math.round((rgb & 0xFF) * (float) DIM_FACTOR);
		return (red << 16) | (green << 8) | blue;
	}

	@Override
	public void resetSession() {
		super.resetSession();
		flaggedAt.clear();
		bottomY.clear();
		topY.clear();
		fresh.clear();
		old.clear();
		terrain.clear();
		appliedSpread = -1;
		appliedSeed = Long.MIN_VALUE;
		seedRule = null;
	}
}
