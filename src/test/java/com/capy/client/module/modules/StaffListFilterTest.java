package com.capy.client.module.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StaffListFilterTest {
	@Test
	void tabAndWorldAreOnline() {
		assertTrue(StaffListFilter.isOnline(true, false, false, false));
		assertTrue(StaffListFilter.isOnline(false, true, false, false));
	}

	@Test
	void offlineAndUnknownAreHidden() {
		assertFalse(StaffListFilter.isOnline(false, false, false, false));
	}

	@Test
	void provenVanishedAndProbeLeakAreShown() {
		assertTrue(StaffListFilter.isOnline(false, false, true, false));
		assertTrue(StaffListFilter.isOnline(false, false, false, true));
	}

	@Test
	void absentTwoSecondsStaysAndAbsentFourSecondsLeaves() {
		assertTrue(StaffListFilter.keepAfterAbsence(false, 2000L));
		assertFalse(StaffListFilter.keepAfterAbsence(false, 4000L));
	}

	@Test
	void onlineWithoutASkinUsesTheUuidDefault() {
		assertEquals(StaffListFilter.HeadChoice.REAL_SKIN,
				StaffListFilter.headChoice(true, true));
		assertEquals(StaffListFilter.HeadChoice.DEFAULT_SKIN,
				StaffListFilter.headChoice(false, true));
		assertEquals(StaffListFilter.HeadChoice.NONE,
				StaffListFilter.headChoice(false, false));
	}

	@Test
	void tenOfflineNeverSeenNamesProduceZeroRowsForOneHundredTicks() {
		int rows = 0;
		for (int tick = 0; tick < 100; tick++) {
			for (int name = 0; name < 10; name++) {
				if (StaffListFilter.shouldConsiderCandidate(false, false)) {
					rows++;
				}
			}
		}
		assertEquals(0, rows);
	}

	@Test
	void onlineNameFadesInOverOneHundredFiftyMilliseconds() {
		float alpha = 0f;
		alpha = StaffListFilter.nextAlpha(alpha, 1f, 50L, 150L);
		assertEquals(0.333f, alpha, 0.002f);
		alpha = StaffListFilter.nextAlpha(alpha, 1f, 50L, 150L);
		assertEquals(0.666f, alpha, 0.002f);
		alpha = StaffListFilter.nextAlpha(alpha, 1f, 50L, 150L);
		assertEquals(1f, alpha, 0.002f);
	}

	@Test
	void absentForThreeSecondsThenFadesOutAndReturnsWithoutAJump() {
		float alpha = StaffListFilter.nextAlpha(1f, 0f, 50L, 150L);
		assertEquals(0.666f, alpha, 0.002f);
		float returned = StaffListFilter.nextAlpha(alpha, 1f, 50L, 150L);
		assertTrue(returned > alpha);
		assertEquals(1f, returned, 0.002f);
	}

	@Test
	void backDuringFadeContinuesFromCurrentAlpha() {
		float current = 0.42f;
		float next = StaffListFilter.nextAlpha(current, 1f, 16L, 150L);
		assertTrue(next > current);
		assertEquals(0.526f, next, 0.01f);
	}
}
