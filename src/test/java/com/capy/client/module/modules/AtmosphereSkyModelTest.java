package com.capy.client.module.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AtmosphereSkyModelTest {
	@Test
	void sunRisesEastAndSetsWest() {
		AtmosphereSkyModel.Sun sunrise = AtmosphereSkyModel.sun(0.25f, 0L);
		AtmosphereSkyModel.Sun noon = AtmosphereSkyModel.sun(0.5f, 0L);
		AtmosphereSkyModel.Sun sunset = AtmosphereSkyModel.sun(0.75f, 0L);
		AtmosphereSkyModel.Sun midnight = AtmosphereSkyModel.sun(0f, 0L);

		assertEquals(90.0f, sunrise.yaw(), 0.5f);
		assertEquals(0.0f, sunrise.pitch(), 0.5f);
		assertTrue(noon.pitch() > 60f, "the noon sun should be high");
		assertTrue(sunset.yaw() < -80f, "the sunset sun should be west");
		assertTrue(midnight.pitch() < -60f, "the midnight sun should be below the horizon");
		assertTrue(noon.daylight() > 0.9f);
		assertTrue(midnight.night() > 0.9f);
	}

	@Test
	void vanillaClockUsesTheSameSunriseConvention() {
		AtmosphereSkyModel.Sun sunrise = AtmosphereSkyModel.sun(-1f, 0L);
		AtmosphereSkyModel.Sun noon = AtmosphereSkyModel.sun(-1f, 6000L);
		AtmosphereSkyModel.Sun sunset = AtmosphereSkyModel.sun(-1f, 12000L);
		AtmosphereSkyModel.Sun midnight = AtmosphereSkyModel.sun(-1f, 18000L);

		assertEquals(90.0f, sunrise.yaw(), 0.5f);
		assertTrue(noon.pitch() > 60f);
		assertTrue(sunset.yaw() < -80f);
		assertTrue(midnight.pitch() < -60f);
	}

	@Test
	void starsAreHiddenInDaylightAndVisibleAtMidnight() {
		assertEquals(0.0f, AtmosphereSkyModel.starVisibility(
				AtmosphereSkyModel.sun(0.5f, 0L)), 0.01f);
		assertEquals(1.0f, AtmosphereSkyModel.starVisibility(
				AtmosphereSkyModel.sun(0f, 0L)), 0.01f);
	}

	@Test
	void moonPhaseComesFromTheWorldDayCount() {
		assertEquals(0.0f, AtmosphereSkyModel.moonPhase(0L), 0.0001f);
		assertEquals(0.5f, AtmosphereSkyModel.moonPhase(4L * 24000L), 0.0001f);
	}

	@Test
	void theDomeIsCoolerAtNoonAndWarmerAtSunset() {
		AtmosphereSkyModel.Sun noon = AtmosphereSkyModel.sun(0.5f, 0L);
		AtmosphereSkyModel.Sun sunset = AtmosphereSkyModel.sun(0.75f, 0L);
		int noonColour = AtmosphereSkyModel.skyColor(0, 0.0, 4.0, noon);
		int sunsetColour = AtmosphereSkyModel.skyColor(0, 0.0, 4.0, sunset);

		int noonBlue = noonColour & 0xFF;
		int sunsetRed = (sunsetColour >> 16) & 0xFF;
		assertTrue(noonBlue > sunsetRed, "noon should be blue, sunset should be warm");
	}
}
