package com.capy.client.module.modules.antiadmin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps track of the alert that is currently on screen and of the cooldown
 * per player.
 *
 * The rules are deliberately simple:
 *   - a watched player the client can observe raises an alert the first time
 *     they are seen;
 *   - while that player stays observable the alert is not raised again until
 *     the cooldown has passed, so one player walking around cannot spam the
 *     screen;
 *   - a different player takes over immediately, because that is the more
 *     urgent situation.
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

	/**
	 * Raises an alert when the cooldown allows it.
	 *
	 * @param matches watched players the client can observe, strongest first
	 * @param cooldownMs cooldown between two alerts for the same player
	 * @param onAlert called when a new alert is raised (used for the sound)
	 */
	public boolean update(List<AdminDetector.Match> matches, long cooldownMs, Runnable onAlert, long now) {
		if (active != null && active.finished(now)) {
			active = null;
		}
		if (matches.isEmpty()) {
			return false;
		}
		AdminDetector.Match strongest = matches.get(0);
		long last = lastAlertAt.getOrDefault(strongest.uuid(), Long.MIN_VALUE / 2);
		if (now - last < cooldownMs) {
			return false;
		}
		lastAlertAt.put(strongest.uuid(), now);
		active = new AdminAlert(strongest, now);
		if (onAlert != null) {
			onAlert.run();
		}
		return true;
	}
}
