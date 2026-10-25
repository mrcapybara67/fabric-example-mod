package com.capy.client.module.modules.antiadmin;

import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Particles only a player can emit, with no player near them.
 *
 * The three types watched are the ones a splash potion, a witch and an instant
 * effect leave behind. A potion thrown by a vanished staff member still draws
 * its particles on every client, which makes this one of the few signals that
 * survives a vanish that removed the thrower completely.
 *
 * One particle is not enough: a pearl, a dispenser or a lingering cloud can
 * produce a stray effect with nobody in sight. Two inside five ticks inside two
 * blocks is a player-sized event.
 */
public final class ParticleDetector extends AbstractDetector {
	private static final int WINDOW_TICKS = 5;
	private static final int REQUIRED = 2;
	private static final double VISIBLE_RADIUS = 2.0;
	/** Two particles from one player are drawn within this of each other. */
	private static final double CLUSTER_RADIUS = 2.0;

	private final Deque<BlockPos> recent = new ArrayDeque<>(REQUIRED);
	private long firstTick;

	public ParticleDetector(SignalBuffer buffer) {
		super(buffer);
	}

	@Override
	public String name() {
		return "orphan_particle";
	}

	@Override
	public DetectionKind kind() {
		return DetectionKind.ORPHAN_PARTICLE;
	}

	@Override
	public void reset() {
		recent.clear();
	}

	@Override
	public void onTick(Minecraft mc) {
		if (recent.isEmpty()) {
			return;
		}
		if (buffer.tick() - firstTick > WINDOW_TICKS) {
			recent.clear();
		}
	}

	@Override
	public void onPacket(PacketSignals.Event event) {
		if (!(event instanceof PacketSignals.Particle particle)) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (nearestOtherPlayer(mc, particle.pos()) <= VISIBLE_RADIUS) {
			return;
		}
		long now = buffer.tick();
		if (!recent.isEmpty()) {
			// Particles from one event are drawn on top of each other; a burst
			// somewhere else is a different event and starts its own count.
			BlockPos first = recent.peekFirst();
			if (now - firstTick > WINDOW_TICKS || first.distSqr(particle.pos()) > CLUSTER_RADIUS * CLUSTER_RADIUS) {
				recent.clear();
			}
		}
		if (recent.isEmpty()) {
			firstTick = now;
		}
		recent.addLast(particle.pos());
		if (recent.size() >= REQUIRED) {
			BlockPos last = recent.peekLast();
			recent.clear();
			emitAt(last, REQUIRED + "x " + particle.tag() + " particles with no player within "
					+ (int) VISIBLE_RADIUS + " blocks");
		}
	}
}
