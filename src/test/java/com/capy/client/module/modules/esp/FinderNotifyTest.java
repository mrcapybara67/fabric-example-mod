package com.capy.client.module.modules.esp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** One toast per module per ten seconds. */
class FinderNotifyTest {

	@Test
	void toastRateLimitIsTenSeconds() {
		assertFalse(FinderNotify.toastAllowed(1_000L, 10_999L));
		assertTrue(FinderNotify.toastAllowed(1_000L, 11_000L));
		assertTrue(FinderNotify.toastAllowed(0L, 60_000L));
	}
}
