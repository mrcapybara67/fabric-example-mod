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
}
