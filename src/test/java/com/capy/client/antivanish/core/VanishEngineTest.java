package com.capy.client.antivanish.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VanishEngineTest {
	private VanishEngine engine;

	@BeforeEach
	void setUp() {
		engine = new VanishEngine();
	}

	@Test
	void criticalNeedsProofAndTwoTypes() {
		VanishEngine.Proof proof = new VanishEngine.Proof("uuid-steve", 4);
		engine.add(VanishEngine.Type.VANISH, "Steve", proof, "Vanish Event: entity remained", 100, 0, 1_000);
		engine.add(VanishEngine.Type.INVISIBLE, "Steve", new VanishEngine.Proof("uuid-steve", 4),
				"Invisible Entity: metadata flag", 30, 0, 1_000);

		VanishEngine.Critical critical = engine.evaluate(1_000);
		assertNotNull(critical);
		assertEquals("Steve", critical.name());
		assertEquals(130, critical.score());
		assertEquals(proof, critical.proof());
	}

	@Test
	void cluesFromDifferentPlayersDoNotFuse() {
		engine.add(VanishEngine.Type.VANISH, "Steve", new VanishEngine.Proof("uuid-steve", 4),
				"Vanish Event: entity remained", 100, 0, 1_000);
		engine.add(VanishEngine.Type.INVISIBLE, "Alex", new VanishEngine.Proof("uuid-alex", 4),
				"Invisible Entity: metadata flag", 35, 0, 1_000);

		assertNull(engine.evaluate(1_000));
	}

	@Test
	void noCriticalWithoutProof() {
		engine.add(VanishEngine.Type.VANISH, "Steve", null, "Vanish Event: hidden from TAB", 100, 0, 1_000);
		engine.add(VanishEngine.Type.INVISIBLE, "Steve", null, "Invisible Entity: metadata flag", 35, 0, 1_000);

		assertNull(engine.evaluate(1_000));
	}

	@Test
	void oneTypeIsNeverCritical() {
		VanishEngine.Proof proof = new VanishEngine.Proof("uuid", 2);
		engine.add(VanishEngine.Type.VANISH, "Steve", proof, "vanish", 100, 0, 1_000);
		engine.add(VanishEngine.Type.VANISH, "Alex", proof, "vanish", 100, 0, 1_000);

		assertNull(engine.evaluate(1_000));
	}

	@Test
	void scoreBelowThresholdIsNotCritical() {
		engine.add(VanishEngine.Type.VANISH, "Steve", new VanishEngine.Proof("uuid", 2), "vanish", 20, 0, 1_000);
		engine.add(VanishEngine.Type.INVISIBLE, "Steve", new VanishEngine.Proof("uuid", 2), "invisible", 10, 0, 1_000);

		assertNull(engine.evaluate(1_000));
	}

	@Test
	void cluesLeaveTheFifteenSecondWindow() {
		engine.add(VanishEngine.Type.VANISH, "Steve", new VanishEngine.Proof("uuid", 2), "vanish", 100, 0, 1_000);
		engine.add(VanishEngine.Type.INVISIBLE, "Steve", new VanishEngine.Proof("uuid", 2), "invisible", 35, 0, 1_000);

		assertNull(engine.evaluate(1_000 + VanishEngine.WINDOW_MS + 1));
	}

	@Test
	void cooldownIsPerTypeAndSubject() {
		VanishEngine.Proof proof = new VanishEngine.Proof("uuid", 2);
		assertTrue(engine.add(VanishEngine.Type.SOUND, "Steve", proof, "sound", 10, 5_000, 1_000));
		assertFalse(engine.add(VanishEngine.Type.SOUND, "Steve", proof, "sound", 10, 5_000, 5_999));
		assertTrue(engine.add(VanishEngine.Type.SOUND, "Alex", proof, "sound", 10, 5_000, 5_999));
		assertTrue(engine.add(VanishEngine.Type.PARTICLE, "Steve", proof, "particle", 10, 5_000, 5_999));
		assertTrue(engine.add(VanishEngine.Type.SOUND, "Steve", proof, "sound", 10, 5_000, 6_000));
	}

	@Test
	void detectionsSortLimitAndExpire() {
		VanishEngine.Proof proof = new VanishEngine.Proof("uuid", 2);
		engine.add(VanishEngine.Type.VANISH, "Bravo", proof, "Vanish Event: hidden from TAB", 50, 0, 1_000);
		engine.add(VanishEngine.Type.INVISIBLE, "Alpha", proof, "Invisible Entity: metadata flag", 50, 0, 1_000);
		engine.add(VanishEngine.Type.SOUND, "Charlie", proof, "sound", 80, 0, 1_000);

		List<VanishEngine.Detection> top = engine.detections(1_000, 2);
		assertEquals(2, top.size());
		assertEquals("Charlie", top.get(0).name());
		assertEquals("Alpha", top.get(1).name());
		assertTrue(engine.detections(1_000 + VanishEngine.TTL_MS, 10).isEmpty());
	}

	@Test
	void leaveMessageClearsVanishClues() {
		VanishEngine.Proof proof = new VanishEngine.Proof("uuid", 2);
		engine.add(VanishEngine.Type.VANISH, "Steve", proof, "Vanish Event: hidden from TAB", 100, 0, 1_000);
		engine.add(VanishEngine.Type.INVISIBLE, "Steve", proof, "Invisible Entity: metadata flag", 35, 0, 1_000);

		engine.forgetVanish("steve");

		assertNull(engine.evaluate(1_000));
		assertTrue(engine.detections(1_000, 10).stream()
				.noneMatch(d -> d.reason().toLowerCase(java.util.Locale.ROOT).startsWith("vanish event")));
	}

	@Test
	void soundAndParticleWithinEightBlocksCluster() {
		engine.addLocated(VanishEngine.Type.SOUND, 0, 64, 0, 20, 1_000);
		engine.addLocated(VanishEngine.Type.PARTICLE, 2, 64, 0, 20, 1_000);

		VanishEngine.Cluster cluster = engine.evaluateCluster(1_000);
		assertNotNull(cluster);
		assertEquals(2, cluster.signals());
		assertEquals(40, cluster.score());
		assertEquals(1.0, cluster.x(), 1e-9);
		assertNull(engine.evaluateCluster(1_001));
	}

	@Test
	void farCluesDoNotCluster() {
		engine.addLocated(VanishEngine.Type.SOUND, 0, 64, 0, 20, 1_000);
		engine.addLocated(VanishEngine.Type.PARTICLE, 9, 64, 0, 20, 1_000);

		assertNull(engine.evaluateCluster(1_000));
	}

	@Test
	void threeSoundsFromOneSpotCluster() {
		for (int i = 0; i < 3; i++) {
			engine.addLocated(VanishEngine.Type.SOUND, 0, 64, 0, 10, 1_000);
		}

		VanishEngine.Cluster cluster = engine.evaluateCluster(1_000);
		assertNotNull(cluster);
		assertEquals(3, cluster.signals());
		assertEquals(30, cluster.score());
	}

	@Test
	void clusterCooldownSuppressesTheNextCluster() {
		engine.addLocated(VanishEngine.Type.SOUND, 0, 64, 0, 20, 1_000);
		engine.addLocated(VanishEngine.Type.PARTICLE, 2, 64, 0, 20, 1_000);
		assertNotNull(engine.evaluateCluster(1_000));

		engine.addLocated(VanishEngine.Type.SOUND, 20, 64, 0, 20, 2_000);
		engine.addLocated(VanishEngine.Type.PARTICLE, 22, 64, 0, 20, 2_000);
		assertNull(engine.evaluateCluster(2_000));
		engine.addLocated(VanishEngine.Type.SOUND, 40, 64, 0, 20, 21_000);
		engine.addLocated(VanishEngine.Type.PARTICLE, 42, 64, 0, 20, 21_000);
		assertNotNull(engine.evaluateCluster(1_000 + VanishEngine.CLUSTER_COOLDOWN_MS));
	}
}
