package com.capy.client.module.modules.esp;

import com.capy.client.finder.core.ChunkStyles;
import com.capy.client.finder.core.Chunks;
import com.capy.client.finder.core.DrawSink;
import com.capy.client.finder.core.ShaftFinder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

/**
 * Chunk Scanner: flags chunks whose tallest shaft is at least ten blocks tall
 * inside the lowest 33-block band.
 *
 * <p>Only the tallest chunk in each eight-connected patch is kept, so one long
 * tunnel becomes one flag instead of a wall of them. A flag older than sixty
 * seconds is drawn in a dimmer colour; it is still a flag, just an older one.</p>
 */
public final class ChunkScannerModule extends ShaftScanModule {
	private static final int MIN_HEIGHT = 10;
	/** Y 0..32 is 33 blocks; elsewhere it starts at Y=0, or a higher world floor. */
	private static final int BAND_HEIGHT = 33;
	private static final long DIM_AFTER_MS = 60_000L;
	private static final double DIM_FACTOR = 0.55;

	private final Map<Long, Long> flaggedAt = new HashMap<>();

	public ChunkScannerModule() {
		super("chunk_scanner", 0xFFFFD54A);
	}

	@Override
	protected int scanDepth() {
		return MIN_HEIGHT;
	}

	@Override
	protected void shaftsChanged() {
		int bandMin = bandMin();
		int bandTop = bandMin + BAND_HEIGHT;
		Map<Long, Integer> tallest = new HashMap<>();
		for (List<ShaftFinder.Shaft> shafts : shaftLists()) {
			for (ShaftFinder.Shaft shaft : shafts) {
				int min = Math.max(shaft.minY(), bandMin);
				int max = Math.min(shaft.maxY(), bandTop);
				int height = max - min;
				if (height < MIN_HEIGHT) {
					continue;
				}
				long key = Chunks.key(shaft.x() >> 4, shaft.z() >> 4);
				tallest.merge(key, height, Math::max);
			}
		}

		Map<Long, Integer> winners = keepTallest(tallest);
		long now = System.currentTimeMillis();
		flaggedAt.keySet().removeIf(key -> !winners.containsKey(key));
		clearFlags();
		for (Map.Entry<Long, Integer> entry : winners.entrySet()) {
			flaggedAt.putIfAbsent(entry.getKey(), now);
			markFlag(entry.getKey(), "shaft " + entry.getValue() + " blocks tall", entry.getValue());
		}
	}

	private int bandMin() {
		if (level == null || level.dimension() == Level.OVERWORLD) {
			return 0;
		}
		// Vanilla's other dimensions also start their usable band at 0. A
		// custom dimension whose floor is higher keeps its own floor instead
		// of reading blocks below the world.
		return Math.max(0, level.getMinY());
	}

	/**
	 * Keeps one chunk per connected patch: the tallest shaft wins, with the
	 * chunk nearest the patch centroid breaking a tie.
	 */
	private static Map<Long, Integer> keepTallest(Map<Long, Integer> heights) {
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
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
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
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.player == null || flags.isEmpty()) {
			return;
		}
		double y = Math.floor(mc.player.getY()) + 0.05;
		int argb = colorArgb();
		int rgb = argb & 0xFFFFFF;
		int alpha = (argb >>> 24) & 0xFF;
		int fill = Math.round(alpha * 0.40f);
		int edge = Math.min(255, Math.max(170, alpha));
		long now = (long) timeMs;
		Set<Long> fresh = new HashSet<>();
		Set<Long> old = new HashSet<>();
		for (long key : flags.keySet()) {
			long since = flaggedAt.getOrDefault(key, now);
			if (now - since >= DIM_AFTER_MS) {
				old.add(key);
			} else {
				fresh.add(key);
			}
		}
		if (!old.isEmpty()) {
			ChunkStyles.fillContour(sink, old, y, dim(rgb), fill, edge, timeSec);
		}
		if (!fresh.isEmpty()) {
			ChunkStyles.fillContour(sink, fresh, y, rgb, fill, edge, timeSec);
		}
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
	}
}
