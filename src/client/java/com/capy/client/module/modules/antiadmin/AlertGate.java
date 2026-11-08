package com.capy.client.module.modules.antiadmin;

/**
 * The two guards every vanish alert passes before it can reach the user.
 *
 * <p>Kept pure so the logout and independent-family rules can be tested without
 * a running client. {@link AlertManager} uses the logout guard when a record is
 * offered, and {@link Correlator} uses it again when it builds the record.</p>
 */
public final class AlertGate {
	private AlertGate() {
	}

	/** True when a server leave line for this name cancels the alert. */
	public static boolean suppressedByLogout(String name) {
		return ChatPresence.leaveWithin(name, ChatPresence.LEAVE_WINDOW_MS);
	}

	/**
	 * Whether a vanish may be announced.
	 *
	 * @param probe    a probe leak confirmed the account
	 * @param families number of independent signal families in the evidence
	 */
	public static boolean mayAnnounceVanish(String name, boolean probe, int families) {
		if (suppressedByLogout(name)) {
			return false;
		}
		return probe || families >= 2;
	}
}
