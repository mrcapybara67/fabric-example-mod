package com.capy.client.module.modules.antiadmin;

import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;

/**
 * Shared plumbing for a detector: the buffer it writes to, the switch state and
 * the two lines that turn a finding into an observation.
 *
 * Subclasses never touch the buffer directly, which is what keeps the window,
 * the tick stamping and the enable check in one place instead of fifteen.
 */
abstract class AbstractDetector implements SignalDetector {
	protected final SignalBuffer buffer;
	private boolean enabled;

	protected AbstractDetector(SignalBuffer buffer) {
		this.buffer = buffer;
	}

	@Override
	public final void onEnable() {
		enabled = true;
		reset();
	}

	@Override
	public final void onDisable() {
		enabled = false;
		reset();
	}

	/** True while the module is on and this detector should be collecting. */
	protected final boolean enabled() {
		return enabled;
	}

	@Override
	public void reset() {
	}

	/** Records one finding with this detector's own kind. */
	protected final void emit(UUID subject, BlockPos position, String evidence) {
		emitAs(kind(), subject, position, evidence);
	}

	/** Records one finding with an explicit kind, for detectors that emit two. */
	protected final void emitAs(DetectionKind kind, UUID subject, BlockPos position, String evidence) {
		if (!enabled || subject == null) {
			return;
		}
		buffer.push(subject, new Observation(kind, buffer.tick(), position, evidence));
	}

	/** Records one finding for a place with nobody to attribute it to. */
	protected final void emitAt(BlockPos position, String evidence) {
		if (position == null) {
			return;
		}
		emit(SignalBuffer.spatial(position), position, evidence);
	}

	/**
	 * Blocks from a point to the closest player, or infinity.
	 *
	 * The local player counts. The question these detectors ask is "was there a
	 * player here who could have caused this", and the client is told about its
	 * own actions with the same packet everyone else gets: the server confirms
	 * the door the user just opened and the crystal the user just placed. A
	 * check that only counted <em>other</em> players therefore reported the
	 * user's own door as opened by nobody and their own crystal as placed by
	 * nobody - which is what made the module shout "someone is here and unseen"
	 * at a player standing alone in their base.
	 */
	protected static double nearestPlayer(Minecraft mc, BlockPos pos) {
		if (mc == null || mc.level == null || pos == null) {
			return Double.POSITIVE_INFINITY;
		}
		double x = pos.getX() + 0.5;
		double y = pos.getY() + 0.5;
		double z = pos.getZ() + 0.5;
		double best = Double.POSITIVE_INFINITY;
		for (AbstractClientPlayer player : mc.level.players()) {
			if (player == null || player.isRemoved()) {
				continue;
			}
			best = Math.min(best, player.distanceToSqr(x, y, z));
		}
		return Math.sqrt(best);
	}

	/**
	 * Blocks from a point to the closest <em>other</em> player, or infinity.
	 *
	 * Only the sound detector uses this, and only because it can: the client
	 * plays its own footsteps, armour, pickups and eating locally and is never
	 * sent those sounds by the server, so a footstep that arrived as a packet
	 * really cannot be the local player's. Every other "nobody was near" check
	 * uses {@link #nearestPlayer}, because there the local player's own action
	 * arrives as a packet too.
	 */
	protected static double nearestOtherPlayer(Minecraft mc, BlockPos pos) {
		if (mc == null || mc.level == null || pos == null) {
			return Double.POSITIVE_INFINITY;
		}
		double x = pos.getX() + 0.5;
		double y = pos.getY() + 0.5;
		double z = pos.getZ() + 0.5;
		double best = Double.POSITIVE_INFINITY;
		for (AbstractClientPlayer player : mc.level.players()) {
			if (player == null || player.isRemoved() || player == mc.player) {
				continue;
			}
			best = Math.min(best, player.distanceToSqr(x, y, z));
		}
		return Math.sqrt(best);
	}
}
