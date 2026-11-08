package com.capy.client.module.modules.antiadmin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfidenceScoringTest {
	@Test
	void severalTabSignalsAreStillOneFamily() {
		Map<DetectionKind, Integer> counts = Map.of(
				DetectionKind.ENTITY_NOT_IN_TAB, 1,
				DetectionKind.SILENT_TAB_REMOVE, 1,
				DetectionKind.SHADOW_MAP_DELTA, 1);
		assertEquals(Confidence.MEDIUM, ConfidenceScoring.score(counts, List.copyOf(counts.keySet())));
	}

	@Test
	void twoIndependentFamiliesRaiseConfidence() {
		Map<DetectionKind, Integer> counts = Map.of(
				DetectionKind.SILENT_TAB_REMOVE, 1,
				DetectionKind.HEADLESS_CHAT, 1);
		assertEquals(Confidence.HIGH, ConfidenceScoring.score(counts, List.copyOf(counts.keySet())));
	}

	@Test
	void probeLeakIsTopConfidenceAlone() {
		Map<DetectionKind, Integer> counts = Map.of(DetectionKind.PROBE_CONFIRMED, 1);
		assertEquals(Confidence.CERTAIN, ConfidenceScoring.score(counts, List.of(DetectionKind.PROBE_CONFIRMED)));
	}
}
