package com.capy.client.module.modules.movement;

/** The boat's automatic sink rule, kept pure so it has a direct test. */
public final class BoatSink {
	private BoatSink() {
	}

	/**
	 * A boat only sinks on its own when there is no input at all. Any forward,
	 * strafe or lift key means the player is actively controlling it.
	 */
	public static double target(double forward, double strafe, double lift, double fallPerSecond) {
		boolean idle = Math.abs(forward) < 1.0e-9 && Math.abs(strafe) < 1.0e-9
				&& Math.abs(lift) < 1.0e-9;
		if (lift > 0.0) {
			return 1.0;
		}
		if (lift < 0.0) {
			return -1.0;
		}
		return idle ? -Math.abs(fallPerSecond) : 0.0;
	}
}
