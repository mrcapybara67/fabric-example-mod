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
 *   0      ms  the elder guardian effect starts (the face)
 *   1500   ms  the face is gone, "&lt;name&gt; is watching you" fades in
 *   2120   ms  "stay safe capy client user" follows underneath
 *   3880   ms  the wording starts to fade out
 *   4300   ms  the alert is over
 * </pre>
 *
 * The face phase is timed from vanilla: the elder guardian particle lives for
 * 30 ticks (1.5 s), and its opacity swells in with a sine. Vanilla then fades
 * it out over the second half because the particle is falling away from the
 * camera. The portrait here travels all the way down the screen instead, so it
 * holds its strength through the fall and only fades over the last fifth - it
 * has to still be readable when it reaches the hotbar. The brightness is
 * raised for the same reason (see {@link #FACE_ALPHA_PEAK}).
 */
public final class AdminAlert {
	/** Why the alert was raised. It only changes the wording. */
	public enum Kind {
		/** The player joined the server: announced once per session. */
		JOINED,
		/** The player is in the world, inside the detection radius. */
		NEARBY,
		/** Unlisted while still connected: a server side vanish. */
		VANISHED,
		/** The tab entry was removed, but their entity is still in the world. */
		GHOST,
		/** Removed completely, yet the server still lists them as online. */
		PROBED,
		/** Listed, but in spectator mode: invisible, and the tab list says so. */
		SPECTATOR,
		/** Listed, but their team hides nametags: the older vanish style. */
		HIDDEN_TAG,
		/**
		 * The evidence has no account attached: a door opened by nobody, a
		 * footstep with no feet. The wording says so instead of inventing a
		 * name for a player the client was never told about.
		 */
		UNSEEN
	}

	/** Vanilla's elder guardian particle lives for 30 ticks. */
	public static final long FACE_MS = 1500L;
	/** The name line starts as soon as the face is gone. */
	public static final long NAME_START_MS = FACE_MS;
	public static final long NAME_FADE_MS = 260L;
	/** The sign-off follows the name. */
	public static final long SIGN_OFF_START_MS = NAME_START_MS + 620L;
	public static final long SIGN_OFF_FADE_MS = 260L;
	/** How long the finished message stays legible. */
	public static final long HOLD_MS = 1500L;
	/** Outro fade. */
	public static final long EXIT_MS = 420L;
	/** Time for the whole overlay to appear. */
	public static final long FADE_IN_MS = 180L;
	/** Total lifetime of the alert. */
	public static final long TOTAL_MS = SIGN_OFF_START_MS + SIGN_OFF_FADE_MS + HOLD_MS + EXIT_MS;

	/**
	 * Opacity at the very start and the end of the face phase.
	 *
	 * Vanilla uses 0.05; a flat portrait is far less visible than a lit 3D
	 * head, so it starts a touch higher.
	 */
	public static final float FACE_ALPHA_MIN = 0.08f;
	/**
	 * Opacity at the peak of the face phase.
	 *
	 * Vanilla peaks at 0.55. A textured quad at that opacity almost disappears
	 * over a bright sky, so the peak is lifted; the shape of the curve is
	 * unchanged. Set this to 0.55f for the literal vanilla value.
	 */
	public static final float FACE_ALPHA_PEAK = 0.85f;
	/** Fraction of the face phase spent rising to full strength. */
	public static final float FACE_RISE = 0.42f;
	/**
	 * Fraction of the face phase where the fade out starts. The portrait is
	 * fully transparent at the end of the phase, which is what makes it vanish
	 * exactly as it reaches the hotbar instead of blinking out mid-air.
	 */
	public static final float FACE_FALL = 0.90f;

	private final String username;
	private final String identity;
	private final double distance;
	private final long ageMs;
	private final PlayerSkin skin;
	private final Kind kind;
	private final long startMs;

	public AdminAlert(AdminDetector.Match match, Kind kind, long startMs) {
		this.username = match.username();
		this.identity = match.identity();
		this.distance = match.distance();
		this.ageMs = match.ageMs();
		this.skin = match.skin();
		this.kind = kind == null ? Kind.NEARBY : kind;
		this.startMs = startMs;
	}

	/** Why this alert was raised. */
	public Kind kind() {
		return kind;
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

	/**
	 * The second line of the notification: how far away they are right now,
	 * or - when the entity is already gone - where they were last seen.
	 *
	 * Empty when the client never had a position for this player.
	 */
	public String detailText() {
		if (distance < 0) {
			return "";
		}
		if (kind == Kind.NEARBY) {
			return String.format(Locale.ROOT, "%.1f blocks away", distance);
		}
		String when = ageMs < 1000L
				? "just now"
				: String.format(Locale.ROOT, "%ds ago", Math.max(1L, ageMs / 1000L));
		return String.format(Locale.ROOT, "last seen %.1f blocks away · %s", distance, when);
	}

	/** Overall visibility, used for the corner notification and the outro. */
	public float alpha(long now) {
		long elapsed = now - startMs;
		if (elapsed < 0 || elapsed >= TOTAL_MS) {
			return 0f;
		}
		if (elapsed < FADE_IN_MS) {
			return elapsed / (float) FADE_IN_MS;
		}
		long exitStart = TOTAL_MS - EXIT_MS;
		if (elapsed <= exitStart) {
			return 1f;
		}
		return 1f - (elapsed - exitStart) / (float) EXIT_MS;
	}

	/** How far the face phase has run, 0..1. Pinned at 1 once it is over. */
	public float faceProgress(long now) {
		return clamp((now - startMs) / (float) FACE_MS);
	}

	/**
	 * Opacity of the face: it swells in from {@link #FACE_ALPHA_MIN}, holds at
	 * {@link #FACE_ALPHA_PEAK} for the fall, and reaches exactly zero at the
	 * end of the phase.
	 */
	public float faceAlpha(long now) {
		long elapsed = now - startMs;
		if (elapsed < 0 || elapsed >= FACE_MS) {
			return 0f;
		}
		float t = elapsed / (float) FACE_MS;
		float rise = smoothstep(0f, FACE_RISE, t);
		float fall = 1f - smoothstep(FACE_FALL, 1f, t);
		return (FACE_ALPHA_MIN + (FACE_ALPHA_PEAK - FACE_ALPHA_MIN) * rise) * fall;
	}

	/** Hermite ramp between two edges, 0 before and 1 after. */
	private static float smoothstep(float edge0, float edge1, float value) {
		float t = clamp((value - edge0) / (edge1 - edge0));
		return t * t * (3f - 2f * t);
	}

	/** Opacity of the "is watching you" line. */
	public float nameAlpha(long now) {
		return lineAlpha(now, NAME_START_MS, NAME_FADE_MS);
	}

	/** Opacity of the sign-off line. */
	public float signOffAlpha(long now) {
		return lineAlpha(now, SIGN_OFF_START_MS, SIGN_OFF_FADE_MS);
	}

	/**
	 * A text line fades in at its own start and then rides the overall
	 * envelope, so both lines leave together at the end.
	 */
	private float lineAlpha(long now, long start, long fadeMs) {
		long elapsed = now - startMs - start;
		if (elapsed <= 0) {
			return 0f;
		}
		float in = clamp(elapsed / (float) Math.max(1L, fadeMs));
		return in * alpha(now);
	}

	/** True once the alert has fully played. */
	public boolean finished(long now) {
		return now - startMs >= TOTAL_MS;
	}

	private static float clamp(float value) {
		return value < 0f ? 0f : (value > 1f ? 1f : value);
	}
}
