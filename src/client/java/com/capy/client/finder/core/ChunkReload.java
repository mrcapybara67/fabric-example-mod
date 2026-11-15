package com.capy.client.finder.core;

/**
 * State machine for the Chunk Reload module: drop the render distance for ten
 * ticks when the player goes below Y 0, restore it, and do not arm again until
 * the player has climbed back above that level.
 *
 * <p>The module owns the option writes; this class only decides when they
 * happen, so the timing can be tested without a running client.</p>
 */
public final class ChunkReload {
	public static final int RESTORE_DELAY_TICKS = 10;
	/** The player-level that clears the cooldown, two blocks above Y -1. */
	public static final int REARM_Y = 1;

	public enum Action { NONE, APPLY, RESTORE }

	private boolean armed = true;
	private boolean applied;
	private int savedDistance = -1;
	private int ticks;
	private int triggerY;

	/**
	 * Advances one client tick.
	 *
	 * @param y             the player's block Y
	 * @param currentDistance the render distance currently configured
	 */
	public Action tick(int y, int currentDistance) {
		if (applied) {
			ticks++;
			if (ticks >= RESTORE_DELAY_TICKS) {
				applied = false;
				armed = false;
				return Action.RESTORE;
			}
			return Action.NONE;
		}
		if (!armed) {
			// Stay disarmed while below the level that re-arms. Requiring both
			// Y 1 and two blocks above the trigger prevents a player who fell
			// to Y -8 from re-applying while still under Y 0.
			if (y >= Math.max(REARM_Y, triggerY + 2)) {
				armed = true;
			}
			return Action.NONE;
		}
		if (y <= -1) {
			savedDistance = currentDistance;
			applied = true;
			ticks = 0;
			triggerY = y;
			return Action.APPLY;
		}
		return Action.NONE;
	}

	/** The distance captured on the last APPLY, or -1 before the first one. */
	public int savedDistance() {
		return savedDistance;
	}

	public boolean applied() {
		return applied;
	}

	public void reset() {
		armed = true;
		applied = false;
		savedDistance = -1;
		ticks = 0;
		triggerY = 0;
	}
}
