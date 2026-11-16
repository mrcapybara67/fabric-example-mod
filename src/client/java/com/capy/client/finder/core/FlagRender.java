package com.capy.client.finder.core;

/** Fixed level-of-detail thresholds and the shared primitive budget. */
public final class FlagRender {
	public enum Detail { FULL, SIMPLE, NONE }

	public static final double FULL_DISTANCE = 160.0;
	public static final double SIMPLE_DISTANCE = 320.0;
	public static final int DEFAULT_BUDGET = 6000;

	private static int remaining = DEFAULT_BUDGET;

	private FlagRender() {
	}

	public static Detail level(double distance) {
		if (distance <= FULL_DISTANCE) {
			return Detail.FULL;
		}
		if (distance <= SIMPLE_DISTANCE) {
			return Detail.SIMPLE;
		}
		return Detail.NONE;
	}

	public static void beginFrame() {
		remaining = DEFAULT_BUDGET;
	}

	/** Reserves a style's estimated primitive cost; false means the flag uses the simple style. */
	public static boolean reserve(int primitives) {
		if (primitives < 0 || primitives > remaining) {
			return false;
		}
		remaining -= primitives;
		return true;
	}

	public static int remaining() {
		return remaining;
	}

	public static void resetForTests() {
		remaining = DEFAULT_BUDGET;
	}
}
