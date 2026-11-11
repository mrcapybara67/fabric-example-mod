package com.capy.client.module.modules.movement;

/**
 * Smooths a velocity towards a target instead of replacing it.
 *
 * <p>{@code next = current * inertia + target * (1 - inertia)} is the same
 * first-order filter used by the camera flight. With the default 0.75 it takes
 * roughly ten ticks to close most of the gap, which is the range a player's
 * own ground acceleration falls into.</p>
 */
public final class InertiaRamp {
	private double x;
	private double y;
	private double z;

	public void reset() {
		x = 0.0;
		y = 0.0;
		z = 0.0;
	}

	/** Advances all three axes by one tick. */
	public void step(double targetX, double targetY, double targetZ, double inertia, boolean legacy) {
		if (legacy) {
			x = targetX;
			y = targetY;
			z = targetZ;
			return;
		}
		double keep = Math.max(0.55, Math.min(0.90, inertia));
		x = next(x, targetX, keep);
		y = next(y, targetY, keep);
		z = next(z, targetZ, keep);
	}

	/** One axis of the same filter, exposed for tests and shared callers. */
	public static double next(double current, double target, double inertia) {
		double keep = Math.max(0.55, Math.min(0.90, inertia));
		return current * keep + target * (1.0 - keep);
	}

	public double x() {
		return x;
	}

	public double y() {
		return y;
	}

	public double z() {
		return z;
	}
}
