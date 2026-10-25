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
	 * Blocks from a point to the closest other player, or infinity.
	 *
	 * The local player is excluded: every one of these signals is about
	 * somebody else, and counting ourselves would make the whole module blind
	 * to a hidden player standing next to us.
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
