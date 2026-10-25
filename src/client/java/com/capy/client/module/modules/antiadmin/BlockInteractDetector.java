package com.capy.client.module.modules.antiadmin;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Doors, trapdoors, buttons, levers and gates that moved on their own.
 *
 * These blocks change state in exactly two ways: a player used them, or
 * redstone did. Since a redstone change also updates the neighbours, a single
 * block update with nobody within five blocks is a player the client cannot
 * see — the quietest, most reliable signal in the module, and the reason it is
 * worth a full UI card on its own.
 *
 * Glass and iron doors are included through the tag: only a player can open one
 * of those, and a plugin-driven open is still a player-driven open.
 */
public final class BlockInteractDetector extends AbstractDetector {
	private static final double VISIBLE_RADIUS = 5.0;
	/** A door changes both halves; one report each is enough. */
	private static final int COOLDOWN_TICKS = 5;

	private final Map<BlockPos, Long> lastReport = new HashMap<>();

	public BlockInteractDetector(SignalBuffer buffer) {
		super(buffer);
	}

	@Override
	public String name() {
		return "headless_block_interact";
	}

	@Override
	public DetectionKind kind() {
		return DetectionKind.HEADLESS_BLOCK_INTERACT;
	}

	@Override
	public void reset() {
		lastReport.clear();
	}

	@Override
	public void onPacket(PacketSignals.Event event) {
		if (!(event instanceof PacketSignals.BlockChange change) || !change.interactive()) {
			return;
		}
		long now = buffer.tick();
		Long previous = lastReport.get(change.pos());
		if (previous != null && now - previous < COOLDOWN_TICKS) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (nearestOtherPlayer(mc, change.pos()) <= VISIBLE_RADIUS) {
			return;
		}
		lastReport.put(change.pos(), now);
		emitAt(change.pos(), "the block at " + change.pos().toShortString()
				+ " changed with no player within " + (int) VISIBLE_RADIUS + " blocks");
	}
}
