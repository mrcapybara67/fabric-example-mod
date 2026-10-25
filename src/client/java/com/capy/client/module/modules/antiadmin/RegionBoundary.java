package com.capy.client.module.modules.antiadmin;

import net.minecraft.core.BlockPos;

/**
 * A named box with a confidence floor.
 *
 * Regions exist for one reason: a detection inside them is more alarming than
 * the same detection in the middle of nowhere. A staff member who appears next
 * to spawn on a survival server is a different event from one who appears at
 * the edge of the world, and the alert level should say so.
 *
 * {@code alertOverride} may be null, which means "leave the computed level
 * alone".
 */
public record RegionBoundary(String name, BlockPos min, BlockPos max, Confidence alertOverride) {

	/** True when the position is inside the box, edges included. */
	public boolean contains(BlockPos pos) {
		return pos != null
				&& pos.getX() >= min.getX() && pos.getX() <= max.getX()
				&& pos.getY() >= min.getY() && pos.getY() <= max.getY()
				&& pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
	}

	/** The level this region guarantees, or {@code computed} when it sets none. */
	public Confidence apply(Confidence computed) {
		return alertOverride == null ? computed : computed.atLeastOf(alertOverride);
	}

	/** Volume in blocks, used to sanity check a hand written profile. */
	public long volume() {
		long dx = (long) max.getX() - min.getX() + 1;
		long dy = (long) max.getY() - min.getY() + 1;
		long dz = (long) max.getZ() - min.getZ() + 1;
		return Math.max(0L, dx * dy * dz);
	}
}
