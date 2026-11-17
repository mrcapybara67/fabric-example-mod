package com.capy.client.antivanish.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SoundGateTest {
	@Test
	void cleanGhostSoundTriggers() {
		assertTrue(SoundGate.soundTriggers(context(true, false, false, false, 1_000, false, false, false)));
	}

	@Test
	void eachSoundFalsePositiveBlocksTheSound() {
		assertFalse(SoundGate.soundTriggers(context(false, false, false, false, 1_000, false, false, false)));
		assertFalse(SoundGate.soundTriggers(context(true, true, false, false, 1_000, false, false, false)));
		assertFalse(SoundGate.soundTriggers(context(true, false, true, false, 1_000, false, false, false)));
		assertFalse(SoundGate.soundTriggers(context(true, false, false, true, 1_000, false, false, false)));
		assertFalse(SoundGate.soundTriggers(context(true, false, false, false, 500, false, false, false)));
		assertFalse(SoundGate.soundTriggers(context(true, false, false, false, 1_000, true, false, false)));
		assertFalse(SoundGate.soundTriggers(context(true, false, false, false, 1_000, false, true, false)));
	}

	@Test
	void busyContainerOnlyBlocksContainerSounds() {
		assertFalse(SoundGate.soundTriggers(context(true, false, false, false, 1_000, false, false, true, true)));
		assertTrue(SoundGate.soundTriggers(context(true, false, false, false, 1_000, false, false, true, false)));
	}

	@Test
	void soundAndContainerListsMatchTheSuspiciousMechanisms() {
		assertTrue(SoundGate.suspiciousSound("minecraft:block.chest.open"));
		assertTrue(SoundGate.suspiciousSound("minecraft:block.iron_door.close"));
		assertTrue(SoundGate.suspiciousSound("minecraft:block.wooden_trapdoor.open"));
		assertTrue(SoundGate.suspiciousSound("minecraft:block.oak_fence_gate.open"));
		assertTrue(SoundGate.suspiciousSound("minecraft:block.stone_button.click"));
		assertTrue(SoundGate.suspiciousSound("minecraft:block.lever.click"));
		assertFalse(SoundGate.suspiciousSound("minecraft:entity.player.step"));

		assertTrue(SoundGate.containerSound("minecraft:block.chest.open"));
		assertTrue(SoundGate.containerSound("minecraft:block.barrel.close"));
		assertTrue(SoundGate.containerSound("minecraft:block.shulker_box.open"));
		assertFalse(SoundGate.containerSound("minecraft:block.iron_door.open"));
	}

	@Test
	void critParticlesTriggerImmediately() {
		SoundGate.ParticleBurst burst = new SoundGate.ParticleBurst();
		assertTrue(SoundGate.particleTriggers("minecraft:crit", true, false, false, 1_000,
				false, false, burst, 1_000));
	}

	@Test
	void smokeNeedsTwoWithinTwoSeconds() {
		SoundGate.ParticleBurst burst = new SoundGate.ParticleBurst();
		assertFalse(SoundGate.particleTriggers("minecraft:smoke", true, false, false, 1_000,
				false, false, burst, 1_000));
		assertTrue(SoundGate.particleTriggers("minecraft:smoke", true, false, false, 1_000,
				false, false, burst, 2_000));
	}

	@Test
	void smokeBesideAnAmbientSourceIsIgnored() {
		SoundGate.ParticleBurst burst = new SoundGate.ParticleBurst();
		assertFalse(SoundGate.particleTriggers("minecraft:smoke", true, false, false, 1_000,
				true, false, burst, 1_000));
		// It did not enter the burst queue, so one later non-ambient smoke is still weak.
		assertFalse(SoundGate.particleTriggers("minecraft:smoke", true, false, false, 1_000,
				false, false, burst, 1_001));
	}

	@Test
	void weakBlockParticlesRightNextToMeAreIgnored() {
		SoundGate.ParticleBurst burst = new SoundGate.ParticleBurst();
		assertFalse(SoundGate.particleTriggers("minecraft:block", true, false, false, 1_000,
				false, true, burst, 1_000));
		assertFalse(SoundGate.particleTriggers("minecraft:block", true, false, false, 1_000,
				false, false, burst, 1_001));
		assertTrue(SoundGate.particleTriggers("minecraft:block", true, false, false, 1_000,
				false, false, burst, 1_002));
	}

	@Test
	void particleFalsePositivesBlockBeforeTheBurstQueue() {
		SoundGate.ParticleBurst burst = new SoundGate.ParticleBurst();
		assertFalse(SoundGate.particleTriggers("minecraft:crit", false, false, false, 1_000,
				false, false, burst, 1_000));
		assertFalse(SoundGate.particleTriggers("minecraft:crit", true, true, false, 1_000,
				false, false, burst, 1_000));
		assertFalse(SoundGate.particleTriggers("minecraft:crit", true, false, true, 1_000,
				false, false, burst, 1_000));
		assertFalse(SoundGate.particleTriggers("minecraft:crit", true, false, false, 500,
				false, false, burst, 1_000));
	}

	private static SoundGate.Context context(boolean nearMe, boolean visibleCause, boolean explosionRelated,
			boolean poweredMechanism, long msSinceMyAction, boolean selfPlaced, boolean villagerDoor,
			boolean myContainerBusy) {
		return context(nearMe, visibleCause, explosionRelated, poweredMechanism, msSinceMyAction, selfPlaced,
				villagerDoor, myContainerBusy, false);
	}

	private static SoundGate.Context context(boolean nearMe, boolean visibleCause, boolean explosionRelated,
			boolean poweredMechanism, long msSinceMyAction, boolean selfPlaced, boolean villagerDoor,
			boolean myContainerBusy, boolean containerSound) {
		return new SoundGate.Context(nearMe, visibleCause, explosionRelated, poweredMechanism, msSinceMyAction,
				selfPlaced, villagerDoor, myContainerBusy, containerSound);
	}
}
