package com.capy.client.module.modules.movement;

import com.google.gson.JsonObject;

/**
 * One server's movement calibration.
 *
 * <p>The profile stores the cap in blocks per second, not a packet delta, so
 * the same value means the same thing in Fly and Boat Fly. Inertia is kept per
 * profile because a server with a strict check can need a longer ramp than a
 * lenient one.</p>
 */
public final class MovementProfile {
	public static final double DEFAULT_HORIZONTAL = 8.0;
	public static final double DEFAULT_VERTICAL = 6.0;
	public static final double DEFAULT_INERTIA = 0.75;
	private static final double MIN_SPEED = 3.0;
	private static final double MAX_SPEED = 40.0;
	private static final double STEP_HORIZONTAL = 0.75;
	private static final double STEP_VERTICAL = 0.50;

	private final String host;
	private double horizontal;
	private double vertical;
	private double inertia;

	public MovementProfile(String host, double horizontal, double vertical, double inertia) {
		this.host = host == null || host.isBlank() ? "generic" : host;
		this.horizontal = clamp(horizontal, MIN_SPEED, MAX_SPEED);
		this.vertical = clamp(vertical, MIN_SPEED, MAX_SPEED);
		this.inertia = clamp(inertia, 0.55, 0.90);
	}

	public static MovementProfile defaults(String host) {
		return new MovementProfile(host, DEFAULT_HORIZONTAL, DEFAULT_VERTICAL, DEFAULT_INERTIA);
	}

	public String host() {
		return host;
	}

	public double horizontal() {
		return horizontal;
	}

	public double vertical() {
		return vertical;
	}

	public double inertia() {
		return inertia;
	}

	/** Lowers a profile by one fixed step after a suspected kick. */
	public void lower() {
		horizontal = Math.max(MIN_SPEED, horizontal - STEP_HORIZONTAL);
		vertical = Math.max(MIN_SPEED, vertical - STEP_VERTICAL);
	}

	/** Raises the threshold after the user marks a flag wrong. */
	public void raise() {
		horizontal = Math.min(MAX_SPEED, horizontal + STEP_HORIZONTAL);
		vertical = Math.min(MAX_SPEED, vertical + STEP_VERTICAL);
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("horizontal", horizontal);
		json.addProperty("vertical", vertical);
		json.addProperty("inertia", inertia);
		return json;
	}

	public static MovementProfile fromJson(String host, JsonObject json) {
		MovementProfile fallback = defaults(host);
		if (json == null) {
			return fallback;
		}
		double h = json.has("horizontal") ? json.get("horizontal").getAsDouble() : fallback.horizontal;
		double v = json.has("vertical") ? json.get("vertical").getAsDouble() : fallback.vertical;
		double i = json.has("inertia") ? json.get("inertia").getAsDouble() : fallback.inertia;
		return new MovementProfile(host, h, v, i);
	}

	private static double clamp(double value, double min, double max) {
		if (!Double.isFinite(value)) {
			return min;
		}
		return Math.max(min, Math.min(max, value));
	}
}
