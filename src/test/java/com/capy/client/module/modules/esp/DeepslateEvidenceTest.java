package com.capy.client.module.modules.esp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A placed deepslate pillar alone must not become a base flag. */
class DeepslateEvidenceTest {

	@Test
	void oneNarrowPillarIsNotEnough() {
		assertFalse(DeepslateFinderModule.enoughEvidence(8, 1, 4, 2));
	}

	@Test
	void spreadOutDeepslateQualifies() {
		assertTrue(DeepslateFinderModule.enoughEvidence(4, 2, 4, 2));
	}

	@Test
	void blocksBelowTheThresholdStillFail() {
		assertFalse(DeepslateFinderModule.enoughEvidence(3, 3, 4, 2));
	}

	@Test
	void aSingleColumnRuleCanBeRestored() {
		assertTrue(DeepslateFinderModule.enoughEvidence(5, 1, 4, 1));
	}
}
