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
 * The local player counts as a player. The crystal the user places and the TNT
 * the user lights arrive as the same spawn packet anybody else's would, so a
 * check that only looked at other players reported every one of them as
 * "spawned with no player nearby" - the exact false alarm of a player blowing
 * up a crystal in their own base. Placing is limited to arm's reach, so a
 * crystal somebody else placed still always has its placer within the radius.
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
		if (nearestPlayer(mc, spawn.pos()) <= VISIBLE_RADIUS) {
			return;
		}
		String what = spawn.type() == net.minecraft.world.entity.EntityType.END_CRYSTAL ? "end crystal" : "TNT";
		emitAt(spawn.pos(), what + " spawned with no player within " + (int) VISIBLE_RADIUS + " blocks");
	}
}
