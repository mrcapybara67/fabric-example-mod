package com.capy.client.module.modules.antiadmin;

import java.util.Locale;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * One raised alert: who was seen, how far away they were and which skin was
 * available.
 *
 * The alert owns its own timeline, so the renderer can stage the sequence
 * without keeping a second clock:
 *
 * <pre>
 *   0 ms     the screen starts to darken and the identity fades in
 *   2600 ms  the face has travelled all the way down and is gone
 * </pre>
 *
 * The movement is the whole effect. There is no ring, no bloom, no petal
 * burst and no pulsing anything: the watched player's face drifts down the
 * screen once and disappears, the way the elder guardian's curse slides past
 * in vanilla, and that is all it does.
 */
public final class AdminAlert {
	/** How long the face takes to travel from above the screen to below it. */
	public static final long TRAVEL_MS = 2600L;
	/** Fade in of the veil and the identity. */
	public static final long FADE_IN_MS = 220L;
	/** Fade out at the very end, once the face has almost left. */
	public static final long EXIT_MS = 420L;

	private final String username;
	private final String identity;
	private final double distance;
	private final PlayerSkin skin;
	private final long startMs;

	public AdminAlert(AdminDetector.Match match, long startMs) {
		this.username = match.username();
		this.identity = match.identity();
		this.distance = match.distance();
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

	/** The player's skin, or null when the client has none. */
	public PlayerSkin skin() {
		return skin;
	}

	public long startMs() {
		return startMs;
	}

	/** The distance, ready to be drawn. */
	public String distanceText() {
		return String.format(Locale.ROOT, "%.1f blocks away", distance);
	}

	/** Visibility of the veil and the identity, including the exit fade. */
	public float alpha(long now) {
		long elapsed = now - startMs;
		if (elapsed < 0) {
			return 0f;
		}
		if (elapsed < FADE_IN_MS) {
			return elapsed / (float) FADE_IN_MS;
		}
		long outStart = TRAVEL_MS - EXIT_MS;
		if (elapsed <= outStart) {
			return 1f;
		}
		if (elapsed >= TRAVEL_MS) {
			return 0f;
		}
		return 1f - (elapsed - outStart) / (float) EXIT_MS;
	}

	/**
	 * How far the face has travelled, 0 at the top of the screen and 1 once it
	 * has left through the bottom. Eased so it enters gently and leaves
	 * gently, which is what makes the movement feel deliberate instead of
	 * mechanical.
	 */
	public float travel(long now) {
		float t = clamp((now - startMs) / (float) TRAVEL_MS);
		if (t < 0.5f) {
			return 4f * t * t * t;
		}
		float f = -2f * t + 2f;
		return 1f - (f * f * f) / 2f;
	}

	/** Progress of the identity reveal, 0..1. */
	public float reveal(long now) {
		return clamp((now - startMs - FADE_IN_MS) / 260f);
	}

	/** True once the alert has fully played. */
	public boolean finished(long now) {
		return now - startMs >= TRAVEL_MS;
	}

	private static float clamp(float value) {
		return value < 0f ? 0f : (value > 1f ? 1f : value);
	}
}
