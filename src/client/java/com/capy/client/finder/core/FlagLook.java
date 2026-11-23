package com.capy.client.finder.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The shared world-space flag look: a translucent terrain plate, a bright
 * contour rim, a beacon core and up to three thin tracers.
 *
 * <p>Everything is submitted through {@link DrawSink}, so this class is
 * testable without a client and every primitive is either a four-vertex quad
 * or a two-vertex line. The caller supplies terrain heights from the
 * {@code TerrainHeights} cache and the module's colour; this class only decides
 * layers and level of detail.</p>
 */
public final class FlagLook {
	public static final int PLATE_ALPHA = 60;
	public static final int RIM_ALPHA = 235;
	public static final double PLATE_LIFT = 0.01;
	public static final double RIM_LIFT = 0.02;
	public static final double CORE_LIFT = 1.5;
	public static final int TRACER_LIMIT = 3;
	/** Five shells (30 quads) plus four posts and two beam quads. */
	public static final int CORE_PRIMITIVES = 36;
	/** Five shells, no posts and no beam. */
	public static final int SHELL_PRIMITIVES = 30;
	public static final long EASE_MS = 350L;

	private static final Set<String> CHUNK_FLAGS = Set.of(
			"stalled_growth_finder", "hive_finder", "chunk_scanner", "deepslate_finder",
			"motion_scanner", "sound_bypass", "activity_inspector", "bedrock_void",
			"artificial_light", "light_finder", "shaft_finder");

	public enum Layer { NONE, BASE, SHELLS, FULL }

	/**
	 * One flag to draw. {@code coreY} may be NaN, in which case the core sits
	 * {@link #CORE_LIFT} blocks above the highest centre terrain cell.
	 */
	public record Flag(long key, double x, double z, int[] heights, int rgb, double coreY,
			double distance, double ease, boolean plate, boolean rim, boolean tracer, boolean beam,
			double beamHeight) {
		public static Flag chunk(long key, int[] heights, int rgb, double distance, double ease,
				boolean tracer) {
			return chunk(key, heights, rgb, distance, ease, tracer, true, 60.0);
		}

		public static Flag chunk(long key, int[] heights, int rgb, double distance, double ease,
				boolean tracer, boolean beam) {
			return chunk(key, heights, rgb, distance, ease, tracer, beam, 60.0);
		}

		public static Flag chunk(long key, int[] heights, int rgb, double distance, double ease,
				boolean tracer, boolean beam, double beamHeight) {
			return new Flag(key, Chunks.x(key) * 16.0 + 8.0, Chunks.z(key) * 16.0 + 8.0,
					heights, rgb, Double.NaN, distance, ease, true, true, tracer, beam, beamHeight);
		}

		public static Flag at(long key, double x, double z, int[] heights, int rgb,
				double distance, double ease, boolean tracer) {
			return at(key, x, z, heights, rgb, distance, ease, tracer, true, 60.0);
		}

		public static Flag at(long key, double x, double z, int[] heights, int rgb,
				double distance, double ease, boolean tracer, boolean beam) {
			return at(key, x, z, heights, rgb, distance, ease, tracer, beam, 60.0);
		}

		public static Flag at(long key, double x, double z, int[] heights, int rgb,
				double distance, double ease, boolean tracer, boolean beam, double beamHeight) {
			return new Flag(key, x, z, heights, rgb, Double.NaN, distance, ease, true, true, tracer, beam, beamHeight);
		}

		/** A terrain plate with the core at the event's own depth. */
		public static Flag atDepth(long key, double x, double y, double z, int[] heights, int rgb,
				double distance, double ease, boolean tracer, boolean beam) {
			return new Flag(key, x, z, heights, rgb, y, distance, ease, true, true, tracer, beam, 60.0);
		}

		public static Flag coreOnly(double x, double y, double z, int rgb, double distance,
				double ease, boolean tracer) {
			return new Flag(0L, x, z, null, rgb, y, distance, ease, false, false, tracer, true, 60.0);
		}
	}

	private FlagLook() {
	}

	/** Modules in Task 3 use this look; the excluded overlays do not. */
	public static boolean isChunkFlagModule(String id) {
		return id != null && CHUNK_FLAGS.contains(id);
	}

	public static Set<String> chunkFlagModules() {
		return CHUNK_FLAGS;
	}

	/**
	 * High draws every layer, Balanced omits posts and beam, Low keeps only the
	 * plate and rim. A distance beyond 320 draws nothing; between 160 and 320
	 * everything except posts and beam is still drawn.
	 */
	public static Layer layer(double distance, FlagQuality quality, boolean autoLower) {
		FlagRender.Detail detail = FlagRender.level(distance);
		if (detail == FlagRender.Detail.NONE) {
			return Layer.NONE;
		}
		FlagQuality q = quality == null ? FlagQuality.HIGH : quality;
		if (q == FlagQuality.LOW) {
			return Layer.BASE;
		}
		if (detail == FlagRender.Detail.SIMPLE) {
			return Layer.SHELLS;
		}
		if (autoLower) {
			return q == FlagQuality.HIGH ? Layer.SHELLS : Layer.BASE;
		}
		return q == FlagQuality.BALANCED ? Layer.SHELLS : Layer.FULL;
	}

	public static double easeOutCubic(long appearedAtMs, double timeMs) {
		if (appearedAtMs <= 0L) {
			return 1.0;
		}
		double t = (timeMs - appearedAtMs) / (double) EASE_MS;
		if (t <= 0.0) {
			return 0.0;
		}
		if (t >= 1.0) {
			return 1.0;
		}
		double inverse = 1.0 - t;
		return 1.0 - inverse * inverse * inverse;
	}

	/** Highest of the four centre cells, which is where the beacon sits. */
	public static int centreHeight(int[] heights) {
		if (heights == null || heights.length != TerrainPlate.CELLS * TerrainPlate.CELLS) {
			return Integer.MIN_VALUE;
		}
		int middle = TerrainPlate.CELLS / 2;
		int best = Integer.MIN_VALUE;
		for (int z = middle - 1; z <= middle; z++) {
			for (int x = middle - 1; x <= middle; x++) {
				best = Math.max(best, heights[z * TerrainPlate.CELLS + x]);
			}
		}
		return best;
	}

	public static void drawAll(DrawSink sink, List<Flag> flags, double timeMs, FlagQuality quality,
			boolean autoLower, boolean tracers, double[] eye) {
		if (sink == null || flags == null || flags.isEmpty()) {
			return;
		}
		List<Flag> ordered = new ArrayList<>(flags.size());
		for (Flag flag : flags) {
			if (flag != null && Double.isFinite(flag.distance) && Double.isFinite(flag.x)
					&& Double.isFinite(flag.z)) {
				ordered.add(flag);
			}
		}
		// Nearest first: the primitive budget must reach the flags that matter.
		ordered.sort(Comparator.comparingDouble(Flag::distance));
		Set<Long> plated = new HashSet<>();
		for (Flag flag : ordered) {
			Layer layer = layer(flag.distance, quality, autoLower);
			if (layer == Layer.NONE) {
				continue;
			}
			double average = flag.heights == null ? Double.NaN : TerrainPlate.average(flag.heights);
			if (flag.plate && flag.heights != null && Double.isFinite(average) && plated.add(flag.key)) {
				TerrainPlate.emit(sink, Chunks.x(flag.key), Chunks.z(flag.key), flag.heights,
						ChunkStyles.argb(PLATE_ALPHA, flag.rgb), PLATE_LIFT);
				if (flag.rim) {
					// A separate lift from the plate: the rim sits on top of it
					// instead of sharing its depth.
					ChunkStyles.rim(sink, Set.of(flag.key), average + PLATE_LIFT + RIM_LIFT,
							flag.rgb, RIM_ALPHA);
				}
			}
			double coreY = coreY(flag);
			if (!Double.isFinite(coreY)) {
				continue;
			}
			Layer coreLayer = layer;
			if (coreLayer == Layer.BASE && !flag.plate && !flag.rim) {
				// A shaft core has no plate to fall back to; its smallest
				// surviving form is the shell-only core.
				coreLayer = Layer.SHELLS;
			}
			if (coreLayer == Layer.FULL && !FlagRender.reserve(CORE_PRIMITIVES)) {
				coreLayer = Layer.BASE;
			} else if (coreLayer == Layer.SHELLS && !FlagRender.reserve(SHELL_PRIMITIVES)) {
				coreLayer = Layer.BASE;
			}
			double ease = Math.max(0.0, Math.min(1.0, flag.ease));
			if (coreLayer == Layer.FULL) {
				BeaconCore.core(sink, flag.x, coreY, flag.z, flag.rgb, timeMs, ease, flag.beam,
						flag.beamHeight);
			} else if (coreLayer == Layer.SHELLS) {
				BeaconCore.shells(sink, flag.x, coreY, flag.z, flag.rgb, timeMs, ease);
			}
		}
		if (tracers && eye != null && eye.length >= 3
				&& Double.isFinite(eye[0]) && Double.isFinite(eye[1]) && Double.isFinite(eye[2])) {
			int count = 0;
			for (Flag flag : ordered) {
				if (count >= TRACER_LIMIT) {
					break;
				}
				if (!flag.tracer || layer(flag.distance, quality, autoLower) == Layer.NONE) {
					continue;
				}
				double coreY = coreY(flag);
				if (!Double.isFinite(coreY)) {
					continue;
				}
				BeaconCore.tracer(sink, eye[0], eye[1], eye[2], flag.x, coreY, flag.z, flag.rgb);
				count++;
			}
		}
	}

	private static double coreY(Flag flag) {
		if (Double.isFinite(flag.coreY)) {
			return flag.coreY;
		}
		int height = centreHeight(flag.heights);
		return height == Integer.MIN_VALUE ? Double.NaN : height + CORE_LIFT;
	}
}
