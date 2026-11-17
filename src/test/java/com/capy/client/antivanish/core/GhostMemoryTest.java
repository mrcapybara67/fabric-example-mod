package com.capy.client.antivanish.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GhostMemoryTest {
	private static final GhostMemory.Pos POS = new GhostMemory.Pos(0, 64, 0);

	@Test
	void eachSoundBooleanCanBlockTheCleanSignal() {
		assertTrue(SoundGate.soundTriggers(context(true, false, false, false, 1_000, false, false, false)));
		assertFalse(SoundGate.soundTriggers(context(false, false, false, false, 1_000, false, false, false)));
		assertFalse(SoundGate.soundTriggers(context(true, true, false, false, 1_000, false, false, false)));
		assertFalse(SoundGate.soundTriggers(context(true, false, true, false, 1_000, false, false, false)));
		assertFalse(SoundGate.soundTriggers(context(true, false, false, true, 1_000, false, false, false)));
		assertFalse(SoundGate.soundTriggers(context(true, false, false, false, 999, false, false, false)));
		assertFalse(SoundGate.soundTriggers(context(true, false, false, false, 1_000, true, false, false)));
		assertFalse(SoundGate.soundTriggers(context(true, false, false, false, 1_000, false, true, false)));
	}

	@Test
	void actionAndContainerTimesAreMeasuredAndExpire() {
		GhostMemory memory = new GhostMemory();
		assertEquals(Long.MAX_VALUE, memory.msSinceAction(1_000));
		memory.action(1_000);
		assertEquals(500, memory.msSinceAction(1_500));
		assertEquals(1_000, memory.msSinceAction(2_000));
		assertFalse(memory.containerBusy(1_000));
		memory.container(1_000);
		assertTrue(memory.containerBusy(3_500));
		assertFalse(memory.containerBusy(3_501));
	}

	@Test
	void placedBlockSuppressesItsOwnDoorSoundForSixSeconds() {
		GhostMemory memory = new GhostMemory();
		memory.placeAttempt("minecraft:oak_door", POS, 1_000);
		memory.blockUpdate("minecraft:oak_door", POS, 1_100);

		assertTrue(memory.selfPlaced(POS, 7_100));
		assertFalse(SoundGate.soundTriggers(context(true, false, false, false, 1_000,
				memory.selfPlaced(POS, 7_100), false, false)));
		assertFalse(memory.selfPlaced(POS, 7_101));
		assertTrue(SoundGate.soundTriggers(context(true, false, false, false, 1_000,
				memory.selfPlaced(POS, 7_101), false, false)));
	}

	@Test
	void placementConfirmationCoversTheSecondDoorOrBedHalf() {
		GhostMemory memory = new GhostMemory();
		memory.placeAttempt("minecraft:oak_door", new GhostMemory.Pos(0, 64, 0), 1_000);
		memory.blockUpdate("minecraft:oak_door", new GhostMemory.Pos(0, 65, 0), 1_100);

		assertTrue(memory.selfPlaced(new GhostMemory.Pos(0, 65, 0), 2_000));
	}

	@Test
	void poweredDoorStaysSuppressedForFiveSecondsAfterPowerDrops() {
		GhostMemory memory = new GhostMemory();
		memory.markPowered(POS, 1_000);

		assertTrue(memory.poweredMechanism(POS, 6_000));
		assertFalse(SoundGate.soundTriggers(context(true, false, false,
				memory.poweredMechanism(POS, 6_000), 1_000, false, false, false)));
		assertFalse(memory.poweredMechanism(POS, 6_001));
		assertTrue(SoundGate.soundTriggers(context(true, false, false,
				memory.poweredMechanism(POS, 6_001), 1_000, false, false, false)));
	}

	@Test
	void explosionSuppressesInsideRadiusPlusFourUntilThreeSeconds() {
		GhostMemory memory = new GhostMemory();
		memory.addExplosion(0, 64, 0, 2, 1_000);

		assertTrue(memory.explosionRelated(6, 64, 0, 4_000));
		assertFalse(memory.explosionRelated(7, 64, 0, 4_000));
		assertFalse(memory.explosionRelated(6, 64, 0, 5_000));
	}

	@Test
	void smokeBesideAnAmbientSourceIsIgnored() {
		SoundGate.ParticleBurst burst = new SoundGate.ParticleBurst();
		assertFalse(SoundGate.particleTriggers("minecraft:smoke", true, false, false, 1_000,
				true, false, burst, 1_000));
		assertFalse(SoundGate.particleTriggers("minecraft:smoke", true, false, false, 1_000,
				true, false, burst, 1_100));
	}

	@Test
	void ambientAndProximityAreTheParticleGates() {
		SoundGate.ParticleBurst burst = new SoundGate.ParticleBurst();
		assertFalse(SoundGate.particleTriggers("minecraft:smoke", true, false, false, 1_000,
				false, false, burst, 1_000));
		assertTrue(SoundGate.particleTriggers("minecraft:smoke", true, false, false, 1_000,
				false, false, burst, 2_000));

		SoundGate.ParticleBurst blockBurst = new SoundGate.ParticleBurst();
		assertFalse(SoundGate.particleTriggers("minecraft:block", true, false, false, 1_000,
				false, true, blockBurst, 1_000));
		assertFalse(SoundGate.particleTriggers("minecraft:block", true, false, false, 1_000,
				false, false, blockBurst, 1_001));
		assertTrue(SoundGate.particleTriggers("minecraft:block", true, false, false, 1_000,
				false, false, blockBurst, 1_002));
	}

	private static SoundGate.Context context(boolean nearMe, boolean visibleCause, boolean explosionRelated,
			boolean poweredMechanism, long msSinceMyAction, boolean selfPlaced, boolean villagerDoor,
			boolean myContainerBusy) {
		return new SoundGate.Context(nearMe, visibleCause, explosionRelated, poweredMechanism, msSinceMyAction,
				selfPlaced, villagerDoor, myContainerBusy, false);
	}
}
