package com.capy.client.module.modules.esp;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Player ESP — a box around every other player in range.
 *
 * Nothing is cached here on purpose. The client already keeps the player list
 * of the level, and a handful of entities is cheaper to walk than to keep in
 * sync: a box is rebuilt from the player's own bounding box every frame, so it
 * follows them exactly, including the movement between their last two ticks
 * (without the interpolation the box visibly steps ten times a second while
 * the rendered body glides).
 *
 * Spectators and invisible players are included. Seeing someone who is meant
 * to be hidden is the point of the module, and hiding them would need a rule
 * the user never asked for.
 */
public final class PlayerEspModule extends EspModule {
	private final List<AABB> boxes = new ArrayList<>();

	public PlayerEspModule() {
		super("player_esp", 0xFFF25C86, 96, true);
	}

	@Override
	public List<AABB> boxes() {
		Minecraft mc = Minecraft.getInstance();
		boxes.clear();
		ClientLevel level = mc.level;
		if (level == null || mc.player == null) {
			return boxes;
		}
		double limit = range();
		double limitSq = limit * limit;
		float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		for (AbstractClientPlayer player : level.players()) {
			if (player == mc.player || !player.isAlive()) {
				continue;
			}
			if (mc.player.distanceToSqr(player) > limitSq) {
				continue;
			}
			// Shift the tick box by the same interpolation the player model is
			// drawn with, so the outline sits on the rendered body instead of a
			// tick behind it. This keeps the box glued to the body at any frame
			// rate, not just at twenty ticks a second.
			AABB box = player.getBoundingBox();
			Vec3 pos = player.getPosition(partialTick);
			double dx = pos.x - player.getX();
			double dy = pos.y - player.getY();
			double dz = pos.z - player.getZ();
			boxes.add(box.move(dx, dy, dz));
			if (boxes.size() >= MAX_BOXES) {
				break;
			}
		}
		return boxes;
	}

	@Override
	protected void clear() {
		boxes.clear();
	}
}
