package com.capy.client.module.modules;

import com.capy.client.util.Guard;
import java.util.ArrayDeque;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ParticleStatus;

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
 *
 * <h2>Budget</h2>
 * The vanilla Particles setting is the first gate: Minimal spawns nothing and
 * Decreased halves the rate. On top of that, at most six particles are spawned
 * per tick, the whole call has a two millisecond budget, and a rolling window
 * caps how many of this module's particles can be alive in the engine. The
 * window is conservative because the client cannot count another mod's
 * particles, let alone its own after the engine has aged them out.
 */
public final class AtmosphereEffects {
	/** How far around the player the weather is spawned. */
	private static final double RADIUS = 14.0;
	private static final int MAX_PER_CALL = 6;
	private static final int MAX_ALIVE = 160;
	private static final long ALIVE_WINDOW_MS = 4_000L;
	private static final long TIME_BUDGET_NANOS = 2_000_000L;
	private static final ArrayDeque<Long> recentSpawns = new ArrayDeque<>();

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
			ParticleStatus status = mc.options.particles().get();
			if (status == ParticleStatus.MINIMAL || theme < 0) {
				return;
			}
			int allowed = status == ParticleStatus.DECREASED ? MAX_PER_CALL / 2 : MAX_PER_CALL;
			long now = System.currentTimeMillis();
			prune(now);
			if (recentSpawns.size() >= MAX_ALIVE) {
				return;
			}
			long start = System.nanoTime();
			double px = mc.player.getX();
			double py = mc.player.getY();
			double pz = mc.player.getZ();
			int count = switch (theme) {
				case 0 -> 1;
				case 1 -> 2;
				case 2 -> 1;
				case 3 -> 2;
				case 4 -> 3;
				case 5 -> 2;
				case 6 -> 1;
				case 7 -> 1;
				case 8 -> 1;
				case 9 -> 3;
				default -> 0;
			};
			count = Math.min(count, allowed);
			ParticleOptions type = switch (theme) {
				case 1 -> ParticleTypes.SPORE_BLOSSOM_AIR;
				case 2 -> ParticleTypes.END_ROD;
				case 3 -> ParticleTypes.SOUL_FIRE_FLAME;
				case 4 -> ParticleTypes.FALLING_WATER;
				case 5 -> ParticleTypes.BUBBLE;
				case 6 -> ParticleTypes.LAVA;
				case 7 -> ParticleTypes.SPORE_BLOSSOM_AIR;
				case 8 -> ParticleTypes.SCULK_SOUL;
				case 9 -> ParticleTypes.ELECTRIC_SPARK;
				default -> ParticleTypes.SPORE_BLOSSOM_AIR;
			};
			double spread = switch (theme) {
				case 1 -> 0.30;
				case 2 -> 0.12;
				case 3 -> 0.35;
				case 4 -> 0.0;
				case 5 -> 0.25;
				case 6 -> 0.20;
				case 7 -> 0.10;
				case 8 -> 0.15;
				case 9 -> 0.45;
				default -> 0.15;
			};
			double fall = switch (theme) {
				case 4 -> -0.55;
				case 5 -> 0.05;
				case 6 -> -0.10;
				case 8 -> -0.02;
				default -> -0.01;
			};
			for (int i = 0; i < count; i++) {
				if (System.nanoTime() - start >= TIME_BUDGET_NANOS) {
					break;
				}
				double x = px + (Math.random() - 0.5) * RADIUS * 2.0;
				double z = pz + (Math.random() - 0.5) * RADIUS * 2.0;
				double y = py + 2.0 + Math.random() * 9.0;
				double vx = (Math.random() - 0.5) * spread;
				double vz = (Math.random() - 0.5) * spread;
				mc.level.addParticle(type, x, y, z, vx, fall, vz);
				recentSpawns.addLast(now);
			}
		} catch (Throwable failure) {
			Guard.report("atmosphere effects", failure);
		}
	}

	private static void prune(long now) {
		while (!recentSpawns.isEmpty() && now - recentSpawns.peekFirst() >= ALIVE_WINDOW_MS) {
			recentSpawns.pollFirst();
		}
	}
}
