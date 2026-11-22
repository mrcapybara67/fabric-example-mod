package com.capy.client.module.modules.antiadmin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeathTrackerTest {
	private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-000000000002");

	@Test
	void deathHoldsUntilExpiry() {
		DeathTracker deaths = new DeathTracker();
		deaths.death(STEVE, 1_000);

		assertTrue(deaths.isDead(STEVE, 2_000));
		assertFalse(deaths.isDead(STEVE, 1_000 + DeathTracker.HOLD_MS + 1));
	}

	@Test
	void aliveClearsADeathImmediately() {
		DeathTracker deaths = new DeathTracker();
		deaths.death(STEVE, 1_000);
		deaths.alive(STEVE);

		assertFalse(deaths.isDead(STEVE, 1_001));
	}

	@Test
	void deathMessageMarksTheVictim() {
		assertTrue(DeathTracker.looksLikeDeath("Steve was slain by Alex", "Steve"));
		assertTrue(DeathTracker.looksLikeDeath("Steve drowned", "Steve"));
		assertTrue(DeathTracker.looksLikeDeath("Steve hit the ground too hard", "Steve"));
	}

	@Test
	void deathMessageDoesNotMarkTheKiller() {
		assertFalse(DeathTracker.looksLikeDeath("Alex was slain by Steve", "Steve"));
		assertFalse(DeathTracker.looksLikeDeath("Alex was killed by Steve", "Steve"));
	}

	@Test
	void deathMessageNeedsAWholeName() {
		assertFalse(DeathTracker.looksLikeDeath("Steven was slain by Alex", "Steve"));
		DeathTracker deaths = new DeathTracker();
		deaths.death(ALEX, 1_000);
		assertFalse(deaths.isDead(STEVE, 1_000));
	}
}
