package com.capy.client.module.modules.antiadmin;

import net.minecraft.core.BlockPos;

/**
 * One piece of evidence, stamped with the client tick it happened on.
 *
 * The subject is deliberately not part of the record: observations live in a
 * buffer keyed by subject, so a positional signal (a sound with nobody to
 * attribute it to) and a real player share the same type without either of
 * them pretending the other exists.
 *
 * {@code position} is null for signals that carry no place at all, such as a
 * tab list entry disappearing.
 */
public record Observation(DetectionKind kind, long tick, BlockPos position, String evidence) {
	/** True when this observation happened within {@code ticks} of {@code now}. */
	public boolean within(long now, int ticks) {
		long age = now - tick;
		return age >= 0 && age <= ticks;
	}

	/** Evidence plus the place, for the webhook line. */
	public String describe() {
		if (position == null) {
			return evidence;
		}
		return evidence + " at " + position.getX() + ", " + position.getY() + ", " + position.getZ();
	}
}
