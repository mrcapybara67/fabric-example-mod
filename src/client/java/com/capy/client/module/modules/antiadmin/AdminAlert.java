package com.capy.client.module.modules.antiadmin;

import java.util.Locale;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * One raised alert: who was seen, how strong the evidence is, how far away
 * they were and which skin was available.
 *
 * The alert owns its own timeline so the renderer can stage the sequence
 * without keeping a second clock:
 *
 * <pre>
 *   0 ms      the alert starts to appear, a soft pink glow blooms
 *   120 ms    the portrait scales up out of the card
 *   160 ms    the Sakura ring draws itself around the portrait
 *   380 ms    the name and the details are revealed
 *   700 ms    the alert holds, with a slow breathing accent
 *   3300 ms   the alert leaves again, shrinking and fading
 * </pre>
 */
public final class AdminAlert {
	public static final long FADE_IN_MS = 200L;
	public static final long PORTRAIT_START_MS = 120L;
	public static final long PORTRAIT_MS = 320L;
	public static final long RING_START_MS = 170L;
	public static final long RING_MS = 380L;
	public static final long REVEAL_START_MS = 340L;
	public static final long REVEAL_MS = 300L;
	public static final long HOLD_MS = 2600L;
	public static final long EXIT_MS = 520L;

	private final String username;
	private final String identity;
	private final double distance;
	private final Evidence evidence;
	private final PlayerSkin skin;
	private final long startMs;

	public AdminAlert(AdminDetector.Match match, long startMs) {
		this.username = match.username();
		this.identity = match.identity();
		this.distance = match.distance();
		this.evidence = match.evidence();
		this.skin = match.skin();
		this.startMs = startMs;
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

	public Evidence evidence() {
		return evidence;
	}

	/** The player's skin, or null when the client has none. */
	public PlayerSkin skin() {
		return skin;
	}

	public long startMs() {
		return startMs;
	}

	/** True when the client can state a distance. */
	public boolean hasDistance() {
		return evidence.hasDistance();
	}

	/** The distance, ready to be drawn; empty when it cannot be measured. */
	public String distanceText() {
		if (!hasDistance()) {
			return "";
		}
		return String.format(Locale.ROOT, "%.1f blocks away", distance);
	}

	/** Visibility of the whole alert, including the exit. */
	public float alpha(long now) {
		long elapsed = now - startMs;
		if (elapsed < 0) {
			return 0f;
		}
		if (elapsed < FADE_IN_MS) {
			return elapsed / (float) FADE_IN_MS;
		}
		long holdEnd = FADE_IN_MS + HOLD_MS;
		if (elapsed < holdEnd) {
			return 1f;
		}
		long out = elapsed - holdEnd;
		if (out < EXIT_MS) {
			return 1f - out / (float) EXIT_MS;
		}
		return 0f;
	}

	/** Progress of the portrait entrance, 0..1 (also drives the exit shrink). */
	public float portraitProgress(long now) {
		long elapsed = now - startMs - PORTRAIT_START_MS;
		float in = clamp(elapsed / (float) PORTRAIT_MS);
		float out = 1f - clamp((now - startMs - (FADE_IN_MS + HOLD_MS)) / (float) EXIT_MS);
		return Math.min(in, out);
	}

	/** Progress of the ring that draws itself around the portrait. */
	public float ringProgress(long now) {
		long elapsed = now - startMs - RING_START_MS;
		return clamp(elapsed / (float) RING_MS);
	}

	/** Progress of the identity reveal. */
	public float revealProgress(long now) {
		long elapsed = now - startMs - REVEAL_START_MS;
		return clamp(elapsed / (float) REVEAL_MS);
	}

	/** A slow breathing accent used while the alert is on screen. */
	public float pulse(long now) {
		long elapsed = now - startMs;
		if (elapsed < FADE_IN_MS) {
			return 0f;
		}
		float t = (elapsed - FADE_IN_MS) / 1000.0f;
		return 0.5f + 0.5f * (float) Math.sin(t * 2.1f);
	}

	/** True once the alert has fully faded out. */
	public boolean finished(long now) {
		return now - startMs >= FADE_IN_MS + HOLD_MS + EXIT_MS;
	}

	private static float clamp(float value) {
		return value < 0f ? 0f : (value > 1f ? 1f : value);
	}
}
