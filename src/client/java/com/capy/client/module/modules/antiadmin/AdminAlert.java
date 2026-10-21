package com.capy.client.module.modules.antiadmin;

import net.minecraft.world.entity.player.PlayerSkin;

/**
 * One raised alert: who was seen, how far away they were and which skin was
 * available when they were spotted.
 *
 * The alert owns its own animation clock so the overlay can fade in and out
 * without the module having to keep a separate timer.
 */
public final class AdminAlert {
	/** Time the alert is fully visible after fading in. */
	public static final long HOLD_MS = 2600L;
	/** Fade in duration. */
	public static final long FADE_IN_MS = 220L;
	/** Fade out duration. */
	public static final long FADE_OUT_MS = 520L;

	private final String username;
	private final String identity;
	private final double distance;
	/** The distance, formatted once when the alert is raised. */
	private final String distanceText;
	private final PlayerSkin skin;
	private final long startMs;

	public AdminAlert(AdminDetector.Candidate candidate, long startMs) {
		this.username = candidate.username();
		this.identity = candidate.identity();
		this.distance = candidate.distance();
		this.skin = candidate.skin();
		this.startMs = startMs;
		this.distanceText = String.format(java.util.Locale.ROOT, "%.1f blocks away", distance);
	}

	public String username() {
		return username;
	}

	/** The identity that matched the watchlist (may differ from the name). */
	public String identity() {
		return identity;
	}

	public double distance() {
		return distance;
	}

	/** The distance, ready to be drawn. */
	public String distanceText() {
		return distanceText;
	}

	/** The player's skin, or null when the client has none. */
	public PlayerSkin skin() {
		return skin;
	}

	public long startMs() {
		return startMs;
	}

	/** Visibility of the alert at the given time, from 0 to 1. */
	public float alpha(long now) {
		long elapsed = now - startMs;
		if (elapsed < 0) {
			return 0f;
		}
		if (elapsed < FADE_IN_MS) {
			return elapsed / (float) FADE_IN_MS;
		}
		if (elapsed < FADE_IN_MS + HOLD_MS) {
			return 1f;
		}
		long out = elapsed - FADE_IN_MS - HOLD_MS;
		if (out < FADE_OUT_MS) {
			return 1f - out / (float) FADE_OUT_MS;
		}
		return 0f;
	}

	/** True once the alert has fully faded out. */
	public boolean finished(long now) {
		return now - startMs >= FADE_IN_MS + HOLD_MS + FADE_OUT_MS;
	}
}
