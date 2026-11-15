package com.capy.client.finder.core;

/** A small, testable frame-rate budget for the shared finder scanner. */
public final class AdaptiveBudget {
	private AdaptiveBudget() {
	}

	public static long nanosForFps(int fps) {
		if (fps >= 60) {
			return 2_000_000L;
		}
		if (fps >= 30) {
			return 1_000_000L;
		}
		return 500_000L;
	}
}
