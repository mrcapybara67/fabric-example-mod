package com.capy.client.module.modules.antiadmin;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Remembers accounts that have recently died.
 *
 * <p>A server commonly moves a dead player into spectator mode while the death
 * screen is open. That is not a vanish, and the tab list cannot distinguish it
 * from staff using spectator to hide. This memory is the missing half of the
 * check: a spectator tell is only evidence while the account is alive.</p>
 *
 * <p>Death is learned from two places: a loaded player entity whose health is
 * zero, and the server's death message. The message matters because a player
 * who dies out of range is never sent to this client, so their entity cannot
 * provide the answer. A name is only matched when it appears before the death
 * wording, which keeps "Alex was slain by Steve" from marking Steve - the
 * killer - as dead.</p>
 *
 * <p>The state is cleared as soon as the account is seen alive or sends a
 * non-spectator game mode. {@link #HOLD_MS} is only a fallback for a client
 * that never receives either, so a missing packet cannot suppress alerts for
 * the rest of the session.</p>
 */
public final class DeathTracker {
	/** How long a death is remembered without a visible respawn. */
	public static final long HOLD_MS = 5 * 60_000L;
	private static final int MAX_TRACKED = 128;

	private static final List<String> DEATH_MARKERS = List.of(
			"was slain", "was shot", "was killed", "was blown up", "was fireballed",
			"was squashed", "was impaled", "was skewered", "was pummeled", "was smashed",
			"was obliterated", "was struck by lightning", "was struck down",
			"was poked to death", "was doomed to fall", "got killed",
			"died", "death", "drowned", "burned to death", "burnt to death",
			"burned", "burnt", "froze to death", "frozen to death",
			"starved to death", "suffocated in a wall", "withered away",
			"hit the ground too hard", "fell from a high place", "fell too far",
			"walked into fire", "tried to swim in lava", "experienced kinetic energy",
			"went up in flames", "went off with a bang", "left the confines of this world",
			"didn't want to live");

	private final Map<UUID, Long> deaths = new HashMap<>();

	/** Marks an account dead at {@code now}. */
	public void death(UUID uuid, long now) {
		if (uuid == null) {
			return;
		}
		deaths.put(uuid, now);
		if (deaths.size() > MAX_TRACKED) {
			prune(now);
			while (deaths.size() > MAX_TRACKED) {
				UUID oldest = null;
				long oldestAt = Long.MAX_VALUE;
				for (Map.Entry<UUID, Long> entry : deaths.entrySet()) {
					if (entry.getValue() < oldestAt) {
						oldestAt = entry.getValue();
						oldest = entry.getKey();
					}
				}
				if (oldest == null) {
					break;
				}
				deaths.remove(oldest);
			}
		}
	}

	/** Clears a death once the account is visibly alive again. */
	public void alive(UUID uuid) {
		if (uuid != null) {
			deaths.remove(uuid);
		}
	}

	/** Drops a death with the rest of an account's session state. */
	public void remove(UUID uuid) {
		alive(uuid);
	}

	/** True while the account is inside the death grace window. */
	public boolean isDead(UUID uuid, long now) {
		if (uuid == null) {
			return false;
		}
		Long at = deaths.get(uuid);
		if (at == null) {
			return false;
		}
		if (now - at > HOLD_MS) {
			deaths.remove(uuid);
			return false;
		}
		return true;
	}

	public void prune(long now) {
		deaths.entrySet().removeIf(entry -> now - entry.getValue() > HOLD_MS);
	}

	public void clear() {
		deaths.clear();
	}

	/**
	 * True when {@code text} reads as a death message for {@code name}.
	 *
	 * <p>The name has to come before the death wording. Vanilla and most death
	 * plugins put the victim there; putting the killer after the wording keeps
	 * "Alex was slain by Steve" from making Steve look dead.</p>
	 */
	public static boolean looksLikeDeath(String text, String name) {
		if (text == null || name == null || name.isBlank()) {
			return false;
		}
		String lower = text.toLowerCase(Locale.ROOT);
		int marker = firstDeathMarker(lower);
		if (marker < 0) {
			return false;
		}
		String needle = name.toLowerCase(Locale.ROOT);
		for (int at = lower.indexOf(needle); at >= 0; at = lower.indexOf(needle, at + 1)) {
			if (wholeName(lower, at, needle.length()) && at < marker) {
				return true;
			}
		}
		return false;
	}

	private static int firstDeathMarker(String lower) {
		int first = -1;
		for (String marker : DEATH_MARKERS) {
			int at = lower.indexOf(marker);
			if (at >= 0 && (first < 0 || at < first)) {
				first = at;
			}
		}
		return first;
	}

	private static boolean wholeName(String text, int at, int length) {
		boolean left = at == 0 || !nameChar(text.charAt(at - 1));
		int end = at + length;
		boolean right = end >= text.length() || !nameChar(text.charAt(end));
		return left && right;
	}

	private static boolean nameChar(char c) {
		return Character.isLetterOrDigit(c) || c == '_';
	}
}
