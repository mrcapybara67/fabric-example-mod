package com.capy.client.module.modules.antiadmin;

/**
 * Chooses one status from the evidence about a single name.
 *
 * <p>The order is deliberate: a probe leak beats a tab gap, a tab gap beats a
 * spectator flag, a live entity without a tab entry beats chat, and chat alone
 * never produces VANISHED. The result carries the evidence label so the row can
 * show why it says what it says.</p>
 */
public final class PresencePolicy {
	private PresencePolicy() {
	}

	/** The evidence inputs for one watched name. */
	public record Evidence(boolean probeHidden, boolean tabHidden, boolean spectator,
			boolean entityWithoutTab, boolean entityInWorld, boolean listed,
			boolean withinRange, boolean probeAbsent, boolean chatHint) {
	}

	/** The chosen status and its short evidence tag. */
	public record Decision(PresenceStatus status, String evidence) {
	}

	public static Decision decide(Evidence evidence) {
		if (evidence == null) {
			return new Decision(PresenceStatus.UNKNOWN, "none");
		}
		if (evidence.probeHidden()) {
			return new Decision(PresenceStatus.VANISHED, "probe");
		}
		if (evidence.tabHidden()) {
			return new Decision(PresenceStatus.VANISHED, "tab");
		}
		if (evidence.spectator()) {
			return new Decision(PresenceStatus.SPECTATOR, "spectator");
		}
		if (evidence.entityWithoutTab()) {
			return new Decision(PresenceStatus.VANISHED, "entity");
		}
		if (evidence.listed()) {
			if (evidence.entityInWorld() && evidence.withinRange()) {
				return new Decision(PresenceStatus.NEARBY, "nearby");
			}
			return new Decision(PresenceStatus.ONLINE, "tab");
		}
		if (evidence.probeAbsent()) {
			return new Decision(PresenceStatus.OFFLINE, "probe");
		}
		if (evidence.chatHint()) {
			// A system line naming an account is not proof of presence. It is
			// useful as a hint, but it must never be the only reason a row says
			// VANISHED.
			return new Decision(PresenceStatus.UNKNOWN, "chat");
		}
		return new Decision(PresenceStatus.UNKNOWN, "none");
	}
}
