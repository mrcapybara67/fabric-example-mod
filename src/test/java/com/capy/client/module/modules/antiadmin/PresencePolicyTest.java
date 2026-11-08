package com.capy.client.module.modules.antiadmin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PresencePolicyTest {
	private static PresencePolicy.Evidence evidence(boolean probeHidden, boolean tabHidden,
			boolean spectator, boolean entityWithoutTab, boolean entityInWorld, boolean listed,
			boolean withinRange, boolean probeAbsent, boolean chatHint) {
		return new PresencePolicy.Evidence(probeHidden, tabHidden, spectator, entityWithoutTab,
				entityInWorld, listed, withinRange, probeAbsent, chatHint);
	}

	@Test
	void probeLeakWins() {
		assertEquals(PresenceStatus.VANISHED, PresencePolicy.decide(
				evidence(true, true, true, false, false, false, false, false, false)).status());
	}

	@Test
	void tabProofIsVanishedWithoutAProbe() {
		PresencePolicy.Decision decision = PresencePolicy.decide(
				evidence(false, true, false, false, false, false, false, false, false));
		assertEquals(PresenceStatus.VANISHED, decision.status());
		assertEquals("tab", decision.evidence());
	}

	@Test
	void chatAloneIsOnlyAHint() {
		PresencePolicy.Decision decision = PresencePolicy.decide(
				evidence(false, false, false, false, false, false, false, false, true));
		assertEquals(PresenceStatus.UNKNOWN, decision.status());
		assertEquals("chat", decision.evidence());
	}

	@Test
	void listedNearbyAndSpectatorHaveTheirOwnStatuses() {
		assertEquals(PresenceStatus.NEARBY, PresencePolicy.decide(
				evidence(false, false, false, false, true, true, true, false, false)).status());
		assertEquals(PresenceStatus.ONLINE, PresencePolicy.decide(
				evidence(false, false, false, false, false, true, false, false, false)).status());
		assertEquals(PresenceStatus.SPECTATOR, PresencePolicy.decide(
				evidence(false, false, true, false, false, true, false, false, false)).status());
	}

	@Test
	void anAbsentProbeIsOffline() {
		assertEquals(PresenceStatus.OFFLINE, PresencePolicy.decide(
				evidence(false, false, false, false, false, false, false, true, false)).status());
	}
}
