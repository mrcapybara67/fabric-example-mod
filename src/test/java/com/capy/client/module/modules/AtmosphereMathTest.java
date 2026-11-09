package com.capy.client.module.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AtmosphereMathTest {
	@Test
	void duskToMidnightTakesTheShortArc() {
		assertEquals(0.2f, AtmosphereMath.shortestDelta(0.8f, 0.0f), 0.0001f);
		assertEquals(-0.2f, AtmosphereMath.shortestDelta(0.1f, 0.9f), 0.0001f);
	}

	@Test
	void easingFromDuskToMidnightNeverPassesNoon() {
		float value = 0.8f;
		for (int i = 0; i < 40; i++) {
			value = AtmosphereMath.easeTimeOfDay(value, 0.0f, 0.2f);
			assertFalse(i > 0 && value > 0.1f && value < 0.79f, "wrapped the long way through noon");
			assertTrue(value >= 0.0f && value < 1.0f);
		}
		// Midnight is the same point as 1.0 on the circle; the eased value
		// approaches it from below instead of crossing noon.
		assertTrue(value > 0.99f || value < 0.01f);
	}

	@Test
	void disablingTheOverrideSnapsToOff() {
		assertEquals(-1f, AtmosphereMath.easeTimeOfDay(0.8f, -1f, 0.5f), 0.0001f);
	}

	@Test
	void neonCoordinatesAreExactWorldMultiples() {
		for (int i = -20; i <= 20; i++) {
			assertEquals(i * 3.0, AtmosphereMath.neonLineCoordinate(i, 3.0), 0.0);
		}
		assertEquals(0.0, AtmosphereMath.neonLineCoordinate(0, 3.0), 0.0);
	}

	@Test
	void heightCacheRefreshesOnTenTicksOrAChunkChange() {
		assertTrue(AtmosphereMath.heightCacheNeedsRefresh(10L, 0L, false));
		assertFalse(AtmosphereMath.heightCacheNeedsRefresh(9L, 0L, false));
		assertTrue(AtmosphereMath.heightCacheNeedsRefresh(1L, 0L, true));
		assertTrue(AtmosphereMath.heightCacheNeedsRefresh(0L, -1L, false));
	}

	@Test
	void moonTerminatorIsLitAtFullAndDarkAtNew() {
		assertEquals(1.0f, AtmosphereMath.moonLight(0.0f, 0.0f), 0.02f);
		assertEquals(0.0f, AtmosphereMath.moonLight(0.0f, 0.5f), 0.02f);
		assertEquals(0.5f, AtmosphereMath.moonLight(0.0f, 0.25f), 0.02f);
		assertTrue(AtmosphereMath.moonLight(1.0f, 0.25f) < AtmosphereMath.moonLight(-1.0f, 0.25f));
	}
}
