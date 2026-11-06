package com.capy.client.module.modules;

import com.capy.client.util.Guard;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/**
 * The visible part of a theme: weather that belongs to the world but is sent by
 * nobody.
 *
 * <p>Everything is vanilla particles spawned client-side, so nothing is
 * transmitted, nothing is simulated on the server, and the effect survives a
 * renderer replacement such as VulkanMod because it goes through the game's own
 * particle engine rather than a custom pipeline.</p>
 *
 * <p>Sakura is the one theme with an interaction: an entity moving through the
 * petal layer kicks leaves up around its feet. That is done by spawning a small
 * burst beside any entity that is actually moving, which reads as the petals
 * being disturbed without pretending to simulate every leaf.</p>
 */
public final class AtmosphereEffects {
	/** How far around the player the weather is spawned. */
	private static final double RADIUS = 14.0;

	private AtmosphereEffects() {
	}

	/** One client tick of theme weather. Called from the module's own tick. */
	public static void tick(Minecraft mc, int theme) {
		try {
			if (mc == null || mc.player == null || mc.level == null || mc.screen != null) {
				// A menu hides the world: spawning particles for a frame nobody
				// sees only costs bandwidth in the particle engine.
				return;
			}
			if (theme < 0) {
				return;
			}
			double px = mc.player.getX();
			double py = mc.player.getY();
			double pz = mc.player.getZ();
			switch (theme) {
				case 0 -> // default: the quiet baseline, a few pale motes
						spawn(mc, ParticleTypes.SPORE_BLOSSOM_AIR, px, py + 7, pz, 1, 0.15, -0.01);
				case 1 -> // golden hour: slow pollen motes
						spawn(mc, ParticleTypes.SPORE_BLOSSOM_AIR, px, py + 9, pz, 2, 0.30, -0.02);
				case 2 -> // midnight: sparse high sparks
						spawn(mc, ParticleTypes.END_ROD, px, py + 12, pz, 1, 0.12, -0.01);
				case 3 -> // aurora: two colours of drifting wisps
						spawn(mc, ParticleTypes.SOUL_FIRE_FLAME, px, py + 10, pz, 2, 0.35, -0.015);
				case 4 -> // overcast: drizzle
						spawn(mc, ParticleTypes.FALLING_WATER, px, py + 10, pz, 3, 0.0, -0.55);
				case 5 -> // deep ocean: bubbles rising
						spawn(mc, ParticleTypes.BUBBLE, px, py + 1, pz, 2, 0.25, 0.05);
				case 6 -> // crimson: embers
						spawn(mc, ParticleTypes.LAVA, px, py + 8, pz, 1, 0.20, -0.10);
				case 7 -> // sakura: cherry leaves, plus the kick-up below
						spawn(mc, ParticleTypes.CHERRY_LEAVES, px, py + 11, pz, 4, 0.5, -0.06);
				case 8 -> // void: dark soul motes
						spawn(mc, ParticleTypes.SCULK_SOUL, px, py + 9, pz, 1, 0.15, -0.02);
				case 9 -> // neon: electric sparks
						spawn(mc, ParticleTypes.ELECTRIC_SPARK, px, py + 8, pz, 3, 0.45, -0.03);
				default -> {
				}
			}
			if (theme == 7) {
				kickUpPetals(mc);
			}
		} catch (Throwable failure) {
			Guard.report("atmosphere effects", failure);
		}
	}

	/** Sakura only: moving entities disturb the leaves at their feet. */
	private static void kickUpPetals(Minecraft mc) {
		if (mc.player == null || mc.level == null || mc.player.tickCount % 2 != 0) {
			return;
		}
		AABB area = mc.player.getBoundingBox().inflate(10.0);
		for (Entity entity : mc.level.getEntities(mc.player, area)) {
			if (entity == null || entity == mc.player) {
				continue;
			}
			double dx = entity.getX() - entity.xOld;
			double dz = entity.getZ() - entity.zOld;
			if (dx * dx + dz * dz < 0.0004) {
				continue;
			}
			for (int i = 0; i < 2; i++) {
				double vx = (Math.random() - 0.5) * 0.12;
				double vz = (Math.random() - 0.5) * 0.12;
				mc.level.addParticle(ParticleTypes.CHERRY_LEAVES,
						entity.getX() + (Math.random() - 0.5) * 0.8,
						entity.getY() + 0.15 + Math.random() * 0.4,
						entity.getZ() + (Math.random() - 0.5) * 0.8,
						vx, 0.04 + Math.random() * 0.04, vz);
			}
		}
	}

	/** Spawns a small cloud of one particle type around the player. */
	private static void spawn(Minecraft mc, ParticleOptions type, double px, double py, double pz,
			int count, double spread, double fallSpeed) {
		for (int i = 0; i < count; i++) {
			double x = px + (Math.random() - 0.5) * RADIUS * 2.0;
			double z = pz + (Math.random() - 0.5) * RADIUS * 2.0;
			double y = py + 2.0 + Math.random() * 9.0;
			double vx = (Math.random() - 0.5) * spread;
			double vz = (Math.random() - 0.5) * spread;
			mc.level.addParticle(type, x, y, z, vx, fallSpeed, vz);
		}
	}
}
