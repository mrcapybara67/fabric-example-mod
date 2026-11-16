package com.capy.client.finder.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Smoothed per-module draw cost plus the global automatic simplification
 * hysteresis. Time is passed in, so the transitions are testable with a fake
 * clock.
 */
public final class FlagCost {
	public static final double SIMPLIFY_OVER_MS = 3.0;
	public static final double RESTORE_UNDER_MS = 1.5;
	public static final long SIMPLIFY_AFTER_MS = 2_000L;
	public static final long RESTORE_AFTER_MS = 5_000L;
	private static final double SMOOTHING = 0.2;

	private final Map<String, Double> averages = new LinkedHashMap<>();
	private double frameMs;
	private double lastTotalMs;
	private long overSince = -1L;
	private long underSince = -1L;
	private boolean simplified;

	public void add(String moduleId, long nanos) {
		if (moduleId == null || nanos < 0) {
			return;
		}
		double ms = nanos / 1_000_000.0;
		frameMs += ms;
		Double previous = averages.get(moduleId);
		averages.put(moduleId, previous == null ? ms : previous + (ms - previous) * SMOOTHING);
	}

	public void endFrame(long nowMs) {
		lastTotalMs = frameMs;
		frameMs = 0;
		if (lastTotalMs > SIMPLIFY_OVER_MS) {
			if (overSince < 0L) {
				overSince = nowMs;
			}
			underSince = -1L;
			if (!simplified && nowMs - overSince >= SIMPLIFY_AFTER_MS) {
				simplified = true;
			}
		} else {
			overSince = -1L;
			if (simplified) {
				if (lastTotalMs < RESTORE_UNDER_MS) {
					if (underSince < 0L) {
						underSince = nowMs;
					}
					if (nowMs - underSince >= RESTORE_AFTER_MS) {
						simplified = false;
						underSince = -1L;
					}
				} else {
					underSince = -1L;
				}
			}
		}
	}

	public double moduleMs(String moduleId) {
		Double value = averages.get(moduleId);
		return value == null ? 0.0 : value;
	}

	public double lastTotalMs() {
		return lastTotalMs;
	}

	public boolean simplified() {
		return simplified;
	}

	public void reset() {
		averages.clear();
		frameMs = 0;
		lastTotalMs = 0;
		overSince = -1L;
		underSince = -1L;
		simplified = false;
	}
}
