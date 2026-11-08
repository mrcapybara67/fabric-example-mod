package com.capy.client.module.modules.antiadmin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LogoutAndVanishTest {
	@BeforeEach
	void clearChatMemory() {
		ChatPresence.clear();
	}

	@Test
	void normalLogoutWithLeaveMessageCannotRaiseAnAlert() {
		ChatPresence.recordSystem("Steve left the game");
		assertTrue(AlertGate.suppressedByLogout("Steve"));
		assertFalse(AlertGate.mayAnnounceVanish("Steve", false, 2));
		assertFalse(ChatPresence.recent("Steve", 180_000L));
	}

	@Test
	void logoutWithoutLeaveIsOnlyWeakEvidence() {
		ChatPresence.recordSystem("Steve was removed from the server");
		assertFalse(AlertGate.suppressedByLogout("Steve"));
		assertFalse(AlertGate.mayAnnounceVanish("Steve", false, 1));
	}

	@Test
	void aProbeLeakIsEnoughAfterALogoutLookalike() {
		assertTrue(AlertGate.mayAnnounceVanish("Steve", true, 0));
	}

	@Test
	void ownTypedCommandIsNotEvidence() {
		ChatPresence.recordCommand("/tpa Steve");
		assertFalse(ChatPresence.recent("Steve", 180_000L));
		assertFalse(AlertGate.mayAnnounceVanish("Steve", false, 0));
	}

	@Test
	void vanishWithoutALeakStillHasTabEvidence() {
		PresencePolicy.Decision decision = PresencePolicy.decide(new PresencePolicy.Evidence(
				false, true, false, false, false, false, false, false, false));
		assertTrue(decision.status() == PresenceStatus.VANISHED);
	}
}
