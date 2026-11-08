package com.capy.client.module.modules.antiadmin;

/**
 * Independent causes behind observations.
 *
 * Several signals can describe the same event. A vanished player can clear the
 * tab flag, be removed from a team and keep answering pings in the same tick;
 * treating those as three separate clues would turn one cause into certainty.
 * Scoring counts families, not observations, so the evidence has to come from
 * different causes before the confidence rises.
 */
public enum SignalFamily {
	/** The server's tab completion named an account the tab list hides. */
	PROBE,
	/** The account is connected but hidden from the drawn tab list. */
	TAB_HIDDEN,
	/** A listed account is in spectator mode. */
	SPECTATOR,
	/** A server system message mentions a vanish or an unlisted account. */
	CHAT,
	/** A world-space signal with no account attached, such as an orphan sound. */
	WORLD,
	/** A signal that exists to rule a subject out. */
	NEUTRAL
}
