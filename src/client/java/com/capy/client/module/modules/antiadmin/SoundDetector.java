package com.capy.client.module.modules.antiadmin;

import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Footsteps, armour, pickups and eating with nobody to make the sound.
 *
 * These are the four sounds a player body makes and almost nothing else does
 * close up. A vanish that leaves the entity removed still leaves the audio, so
 * an invisible staff member walking around a base is audible even when they are
 * not visible.
 *
 * Three sounds inside ten ticks, all within three blocks of the first, is a
 * person walking. A single sound is a dropped item landing or a zombie in a
 * wall, and reporting those would make the module useless on any populated
 * server.
 */
public final class SoundDetector extends AbstractDetector {
	private static final int WINDOW_TICKS = 10;
	private static final int REQUIRED = 3;
	private static final double VISIBLE_RADIUS = 1.5;
	private static final double CLUSTER_RADIUS = 3.0;

	private final Deque<BlockPos> recent = new ArrayDeque<>(REQUIRED);
	private long firstTick;
	private String lastTag = "sound";

	public SoundDetector(SignalBuffer buffer) {
		super(buffer);
	}

	@Override
	public String name() {
		return "orphan_sound";
	}

	@Override
	public DetectionKind kind() {
		return DetectionKind.ORPHAN_SOUND;
	}

	@Override
	public void reset() {
		recent.clear();
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!recent.isEmpty() && buffer.tick() - firstTick > WINDOW_TICKS) {
			recent.clear();
		}
	}

	@Override
	public void onPacket(PacketSignals.Event event) {
		if (!(event instanceof PacketSignals.SoundAt sound)) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (nearestOtherPlayer(mc, sound.pos()) <= VISIBLE_RADIUS) {
			return;
		}
		long now = buffer.tick();
		if (!recent.isEmpty()) {
			BlockPos first = recent.peekFirst();
			if (now - firstTick > WINDOW_TICKS || first.distSqr(sound.pos()) > CLUSTER_RADIUS * CLUSTER_RADIUS) {
				recent.clear();
			}
		}
		if (recent.isEmpty()) {
			firstTick = now;
		}
		recent.addLast(sound.pos());
		lastTag = sound.tag();
		if (recent.size() >= REQUIRED) {
			BlockPos last = recent.peekLast();
			recent.clear();
			emitAt(last, REQUIRED + "x " + lastTag + " sounds with no player within "
					+ VISIBLE_RADIUS + " blocks");
		}
	}
}
