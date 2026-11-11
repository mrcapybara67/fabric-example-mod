package com.capy.client.module.modules.esp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FinderCalibrationTest {
	@Test
	void thresholdRisesAboveSyntheticBaseline() {
		FinderCalibration calibration = new FinderCalibration();
		for (int i = 0; i < 40; i++) {
			calibration.observe(2.0);
		}
		assertTrue(calibration.threshold(1.0) > 2.0);
	}

	@Test
	void absentSignalStaysQuiet() {
		FinderCalibration calibration = new FinderCalibration();
		for (int i = 0; i < 40; i++) {
			calibration.observe(0.0);
		}
		assertTrue(calibration.absent());
		assertTrue(calibration.threshold(1.0) >= 1.0);
	}

	@Test
	void handFloorWins() {
		FinderCalibration calibration = new FinderCalibration();
		for (int i = 0; i < 40; i++) {
			calibration.observe(1.0);
		}
		assertTrue(calibration.threshold(20.0) >= 20.0);
	}
}
