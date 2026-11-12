package com.capy.client.module.modules.esp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class FinderCalibrationTest {
	@Test
	void syntheticWorldFlagsExactlyTheHighChunks() {
		FinderCalibration calibration = new FinderCalibration();
		int flagged = 0;
		for (int i = 0; i < 200; i++) {
			double score = 1.0 + (i % 5) * 0.05;
			if (calibration.isFlagged(score, 3.0)) {
				flagged++;
			} else {
				calibration.observe(score);
			}
		}
		for (int i = 0; i < 3; i++) {
			double score = 40.0 + i;
			if (calibration.isFlagged(score, 3.0)) {
				flagged++;
			} else {
				calibration.observe(score);
			}
		}
		assertEquals(3, flagged);
	}

	@Test
	void highChunkStillFlagsAfterThousandsOfNormals() {
		FinderCalibration calibration = new FinderCalibration();
		for (int i = 0; i < 5000; i++) {
			double score = 2.0 + (i % 7) * 0.01;
			if (!calibration.isFlagged(score, 3.0)) {
				calibration.observe(score);
			}
		}
		assertTrue(calibration.isFlagged(80.0, 3.0));
		assertFalse(calibration.isFlagged(2.0, 3.0));
	}

	@Test
	void markWrongRaisesTheThreshold() {
		FinderCalibration calibration = new FinderCalibration();
		for (int i = 0; i < 40; i++) {
			calibration.observe(1.0);
		}
		double before = calibration.threshold(1.0);
		calibration.markWrong();
		assertTrue(calibration.threshold(1.0) > before);
	}

	@Test
	void oldAllTimeFormatIsDiscarded() {
		JsonObject old = new JsonObject();
		old.addProperty("samples", 400);
		old.addProperty("sum", 800.0);
		old.addProperty("max", 900.0);
		old.addProperty("manual_floor", 7);
		FinderCalibration calibration = FinderCalibration.fromJson(old);
		assertEquals(0, calibration.samples());
		assertEquals(1.0, calibration.threshold(1.0), 1.0e-9);
	}

	@Test
	void boundedWindowStaysAt256() {
		FinderCalibration calibration = new FinderCalibration();
		for (int i = 0; i < 1000; i++) {
			calibration.observe(i % 4);
		}
		assertEquals(FinderCalibration.WINDOW_SIZE, calibration.samples());
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
}
