package com.capy.client.module.modules;

import com.capy.client.util.Guard;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;

/**
 * The visible part of a theme: weather that belongs to the world but is sent by
 * nobody.
 *
 * <p>Everything is vanilla particles spawned client-side, so nothing is
 * transmitted, nothing is simulated on the server, and the effect survives a
 * renderer replacement such as VulkanMod because it goes through the game's own
 * particle engine rather than a custom pipeline.</p>
 *
 * <p>Sakura does not spawn vanilla cherry-leaf particles: its leaves are the
 * textured opal petals from {@link AtmosphereWorldRenderer}, so the theme has
 * one leaf design rather than a particle and a model that do not match.</p>
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
				case 7 -> // sakura: the sky and world petals carry this theme
						spawn(mc, ParticleTypes.SPORE_BLOSSOM_AIR, px, py + 8, pz, 1, 0.10, -0.01);
				case 8 -> // void: dark soul motes
						spawn(mc, ParticleTypes.SCULK_SOUL, px, py + 9, pz, 1, 0.15, -0.02);
				case 9 -> // neon: electric sparks
						spawn(mc, ParticleTypes.ELECTRIC_SPARK, px, py + 8, pz, 3, 0.45, -0.03);
				default -> {
				}
			}
		} catch (Throwable failure) {
			Guard.report("atmosphere effects", failure);
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
