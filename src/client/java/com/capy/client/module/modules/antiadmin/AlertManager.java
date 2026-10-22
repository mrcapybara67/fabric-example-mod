package com.capy.client.module.modules.antiadmin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps track of the alert that is currently on screen and of the cooldown
 * per player.
 *
 * Only one alert is on screen at a time, so this class is the single place
 * that decides what the player is told next.
 *
 * The rules are deliberately simple:
 *   <ul>
 *     <li>a watched player the client can observe nearby raises an alert the
 *         first time they are seen;</li>
 *     <li>while that player stays nearby the alert is not raised again until
 *         the cooldown has passed, so one player walking around cannot spam
 *         the screen;</li>
 *     <li>a different player takes over immediately, because that is the more
 *         urgent situation;</li>
 *     <li>a join announcement is raised directly through {@link #raise}: it
 *         happens once per session by construction, so it needs no cooldown
 *         and it must not consume the nearby cooldown of the same player.</li>
 *   </ul>
 */
public final class AlertManager {
	private final Map<UUID, Long> lastAlertAt = new HashMap<>();
	private AdminAlert active;

	/** Drops every trace of the previous session (world change, disconnect). */
	public void reset() {
		lastAlertAt.clear();
		active = null;
	}

	/** The alert currently on screen, or null. */
	public AdminAlert active() {
		return active;
	}

	/** Clears the alert once it has finished playing. */
	public void expire(long now) {
		if (active != null && active.finished(now)) {
			active = null;
		}
	}

	/** True while an alert is still on screen. */
	public boolean isBusy(long now) {
		expire(now);
		return active != null;
	}

	/**
	 * Puts an alert on screen immediately, without consulting the cooldown.
	 *
	 * Used for join announcements, which are already one per session and must
	 * be shown even if the same player alerted a moment ago.
	 */
	public boolean raise(AdminDetector.Match match, AdminAlert.Kind kind, long now) {
		if (match == null) {
			return false;
		}
		active = new AdminAlert(match, kind, now);
		return true;
	}

	/**
	 * Raises a proximity alert when the cooldown allows it.
	 *
	 * @param matches watched players the client can observe, closest first
	 * @param cooldownMs cooldown between two alerts for the same player
	 * @param onAlert called when a new alert is raised (used for the sound)
	 */
	public boolean update(List<AdminDetector.Match> matches, long cooldownMs, Runnable onAlert, long now) {
		expire(now);
		if (matches.isEmpty()) {
			return false;
		}
		AdminDetector.Match strongest = matches.get(0);
		long last = lastAlertAt.getOrDefault(strongest.uuid(), Long.MIN_VALUE / 2);
		if (now - last < cooldownMs) {
			return false;
		}
		lastAlertAt.put(strongest.uuid(), now);
		active = new AdminAlert(strongest, AdminAlert.Kind.NEARBY, now);
		if (onAlert != null) {
			onAlert.run();
		}
		return true;
	}
}
