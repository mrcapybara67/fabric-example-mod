package com.capy.client.module.modules;

/** Pure decisions used by the Staff List; kept separate so they can be tested. */
public final class StaffListFilter {
	public enum HeadChoice {
		REAL_SKIN,
		DEFAULT_SKIN,
		NONE
	}

	private StaffListFilter() {
	}

	/**
	 * Online means in the tab list, in the world, proven tab-hidden, or leaked
	 * by a completed probe. Chat or a stale last-seen value is not enough.
	 */
	public static boolean isOnline(boolean inTab, boolean inWorld, boolean provenHidden, boolean probeLeak) {
		return inTab || inWorld || provenHidden || probeLeak;
	}

	/** A row survives a three-second absence, then leaves. */
	public static boolean keepAfterAbsence(boolean online, long absentMs) {
		return online || absentMs <= 3000L;
	}

	/**
	 * An offline name only gets a row if it was online before or is already
	 * fading. A never-seen watchlist name is not a row at all, so ten offline
	 * names cannot create the old four-tick flash loop.
	 */
	public static boolean shouldConsiderCandidate(boolean online, boolean seen) {
		return online || seen;
	}

	/** Moves alpha towards the target at a fixed 150 ms fade rate. */
	public static float nextAlpha(float current, float target, long elapsedMs, long fadeMs) {
		if (elapsedMs <= 0L || fadeMs <= 0L) {
			return Math.max(0f, Math.min(1f, current));
		}
		float step = elapsedMs / (float) fadeMs;
		float next = current + Math.max(-step, Math.min(step, target - current));
		return Math.max(0f, Math.min(1f, next));
	}

	/** Real skin first, the UUID's default skin second, never a letter box. */
	public static HeadChoice headChoice(boolean hasSkin, boolean hasUuid) {
		if (hasSkin) {
			return HeadChoice.REAL_SKIN;
		}
		return hasUuid ? HeadChoice.DEFAULT_SKIN : HeadChoice.NONE;
	}
}
