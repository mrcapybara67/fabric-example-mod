package com.capy.client.module.modules.antiadmin;

import net.minecraft.client.Minecraft;

/**
 * TNT and end crystals that appeared with nobody close enough to have placed
 * or lit them.
 *
 * The spawn packet is the moment the entity enters the world, so the check is
 * "was there a player within six blocks when it appeared". A redstone line or a
 * dispenser produces the same packet with no player present, which is why this
 * is a weak signal: only repetition or a second kind makes it a finding.
 *
 * A player standing more than six blocks away and shooting the crystal is
 * still missed on purpose — guessing at owners would turn every bow fight into
 * an alert.
 */
public final class ExplosionDetector extends AbstractDetector {
	private static final double VISIBLE_RADIUS = 6.0;

	public ExplosionDetector(SignalBuffer buffer) {
		super(buffer);
	}

	@Override
	public String name() {
		return "sourceless_explosion";
	}

	@Override
	public DetectionKind kind() {
		return DetectionKind.SOURCELESS_EXPLOSION;
	}

	@Override
	public void onPacket(PacketSignals.Event event) {
		if (!(event instanceof PacketSignals.Spawn spawn)) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (nearestOtherPlayer(mc, spawn.pos()) <= VISIBLE_RADIUS) {
			return;
		}
		String what = spawn.type() == net.minecraft.world.entity.EntityType.END_CRYSTAL ? "end crystal" : "TNT";
		emitAt(spawn.pos(), what + " spawned with no player within " + (int) VISIBLE_RADIUS + " blocks");
	}
}
