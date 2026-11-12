package com.capy.client.module.modules.esp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Arrays;

/**
 * Per-server robust baseline for a finder.
 *
 * <p>A finder judges a score against the window collected <em>before</em> that
 * score. The caller must only record scores that did not flag, so a site does
 * not raise the bar that is supposed to find it. The window is the last 256
 * non-flagging chunk scores, not an all-time maximum: a busy server can
 * otherwise make the baseline creep upwards for the rest of its life.</p>
 *
 * <p>The baseline is the median plus four median absolute deviations and a
 * small constant. Median/MAD ignores a handful of unusual chunks much better
 * than a mean, while the constant stops a perfectly uniform window from
 * producing a threshold equal to its own samples.</p>
 */
public final class FinderCalibration {
	public static final int WINDOW_SIZE = 256;
	public static final int MIN_SAMPLES = 32;
	public static final int VERSION = 2;
	private static final double BASELINE_PADDING = 0.5;

	private final double[] window = new double[WINDOW_SIZE];
	private final double[] scratch = new double[WINDOW_SIZE];
	private final double[] deviations = new double[WINDOW_SIZE];
	private int next;
	private int count;
	private int manualFloor;

	/**
	 * True when this score is above the baseline built without it.
	 * Callers must not call {@link #observe(double)} for a flagged score.
	 */
	public boolean isFlagged(double score, double configured) {
		return Double.isFinite(score) && score >= threshold(configured);
	}

	/** Records a score that did not flag. Non-finite values are ignored. */
	public void observe(double score) {
		if (!Double.isFinite(score) || score < 0.0) {
			return;
		}
		window[next] = score;
		next = (next + 1) % WINDOW_SIZE;
		if (count < WINDOW_SIZE) {
			count++;
		}
	}

	/**
	 * Threshold for the next judgement.
	 *
	 * <p>Until 32 samples exist only the configured minimum (plus a manual
	 * correction) is used. After that the threshold is the larger of that floor
	 * and the robust baseline.</p>
	 */
	public double threshold(double configured) {
		double floor = Math.max(0.0, configured) + manualFloor;
		if (count < MIN_SAMPLES) {
			return floor;
		}
		System.arraycopy(window, 0, scratch, 0, count);
		Arrays.sort(scratch, 0, count);
		double median = percentile(scratch, count, 0.5);
		for (int i = 0; i < count; i++) {
			deviations[i] = Math.abs(scratch[i] - median);
		}
		Arrays.sort(deviations, 0, count);
		double mad = percentile(deviations, count, 0.5);
		double baseline = median + 4.0 * mad + BASELINE_PADDING;
		return Math.max(floor, baseline);
	}

	private static double percentile(double[] sorted, int length, double fraction) {
		if (length <= 0) {
			return 0.0;
		}
		double position = Math.max(0.0, Math.min(1.0, fraction)) * (length - 1);
		int low = (int) Math.floor(position);
		int high = Math.min(length - 1, low + 1);
		double t = position - low;
		return sorted[low] + (sorted[high] - sorted[low]) * t;
	}

	/** True when the server has produced enough samples and none had a signal. */
	public boolean absent() {
		if (count < 20) {
			return false;
		}
		for (int i = 0; i < count; i++) {
			if (window[i] > 0.0) {
				return false;
			}
		}
		return true;
	}

	/** Raises the floor after the player marks a flag as wrong. */
	public void markWrong() {
		manualFloor++;
	}

	public int samples() {
		return count;
	}

	/** Largest score currently inside the bounded window. */
	public double max() {
		double max = 0.0;
		for (int i = 0; i < count; i++) {
			max = Math.max(max, window[i]);
		}
		return max;
	}

	public void reset() {
		Arrays.fill(window, 0.0);
		next = 0;
		count = 0;
		manualFloor = 0;
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("version", VERSION);
		json.addProperty("manual_floor", manualFloor);
		JsonArray samples = new JsonArray();
		for (int i = 0; i < count; i++) {
			int index = (next - count + i + WINDOW_SIZE) % WINDOW_SIZE;
			samples.add(window[index]);
		}
		json.add("window", samples);
		return json;
	}

	/**
	 * Reads the current format only. The previous all-time sum/max format is
	 * deliberately discarded instead of being reinterpreted: its numbers mean
	 * something different, and loading them would keep the old bug alive.
	 */
	public static FinderCalibration fromJson(JsonObject json) {
		FinderCalibration calibration = new FinderCalibration();
		if (json == null || json.has("version") && json.get("version").getAsInt() != VERSION) {
			return calibration;
		}
		if (!json.has("version") || !json.has("window") || !json.get("window").isJsonArray()) {
			return calibration;
		}
		try {
			calibration.manualFloor = Math.max(0, json.has("manual_floor")
					? json.get("manual_floor").getAsInt() : 0);
			JsonArray values = json.getAsJsonArray("window");
			int start = Math.max(0, values.size() - WINDOW_SIZE);
			for (int i = start; i < values.size(); i++) {
				JsonElement element = values.get(i);
				if (element != null && element.isJsonPrimitive()) {
					calibration.observe(element.getAsDouble());
				}
			}
		} catch (RuntimeException ignored) {
			return new FinderCalibration();
		}
		return calibration;
	}
}
