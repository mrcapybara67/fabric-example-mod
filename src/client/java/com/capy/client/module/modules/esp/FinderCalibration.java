package com.capy.client.module.modules.esp;

import com.google.gson.JsonObject;

/**
 * A small per-server baseline for a finder.
 *
 * <p>The threshold is deliberately relative: a server with more amethyst or
 * more air is not treated as a server full of bases. Only a score clearly
 * above that server's own observed maximum is allowed to flag. A signal that
 * has produced no positive samples is reported as absent rather than guessed
 * at.</p>
 */
public final class FinderCalibration {
	private int samples;
	private double sum;
	private double max;
	private int manualFloor;

	public void observe(double score) {
		if (!Double.isFinite(score)) {
			return;
		}
		samples++;
		sum += Math.max(0, score);
		max = Math.max(max, score);
	}

	/**
	 * @param configured the person's own minimum
	 * @return a threshold above the observed baseline, never below the hand-set
	 *         value
	 */
	public double threshold(double configured) {
		double floor = Math.max(0, configured) + manualFloor;
		if (samples < 8) {
			return floor;
		}
		double mean = sum / samples;
		double relative = mean + (max - mean) * 0.35 + 0.75;
		return Math.max(floor, Math.max(max + 0.5, relative));
	}

	public boolean absent() {
		return samples >= 20 && max <= 0.0;
	}

	public void markWrong() {
		manualFloor++;
	}

	public int samples() {
		return samples;
	}

	public double max() {
		return max;
	}

	public void reset() {
		samples = 0;
		sum = 0;
		max = 0;
		manualFloor = 0;
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("samples", samples);
		json.addProperty("sum", sum);
		json.addProperty("max", max);
		json.addProperty("manual_floor", manualFloor);
		return json;
	}

	public static FinderCalibration fromJson(JsonObject json) {
		FinderCalibration calibration = new FinderCalibration();
		if (json == null) {
			return calibration;
		}
		try {
			calibration.samples = Math.max(0, json.has("samples") ? json.get("samples").getAsInt() : 0);
			calibration.sum = Math.max(0, json.has("sum") ? json.get("sum").getAsDouble() : 0);
			calibration.max = Math.max(0, json.has("max") ? json.get("max").getAsDouble() : 0);
			calibration.manualFloor = Math.max(0, json.has("manual_floor")
					? json.get("manual_floor").getAsInt() : 0);
		} catch (RuntimeException ignored) {
			return new FinderCalibration();
		}
		return calibration;
	}
}
