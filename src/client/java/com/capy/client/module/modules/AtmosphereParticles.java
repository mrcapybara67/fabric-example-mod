package com.capy.client.module.modules;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Arrays;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;

/**
 * The air around the player.
 *
 * <p>This is a fixed pool, not a particle engine: there are no spawn events,
 * no per-frame objects and no random spray above the camera. A slot owns one
 * absolute world position for as long as the theme lasts. When it leaves the
 * box around the camera it wraps to the opposite side, so the same air is
 * always full and nothing ever pops into existence in front of the player.</p>
 *
 * <p>Every slot belongs to one of three depth bands chosen from its index:
 * near (4.5 by 3.5 blocks), mid (11 by 7) and far (22 by 14). The near band is
 * the one that gives petals their size and parallax; the far band fills the
 * horizon without hundreds of large quads.</p>
 *
 * <p>Motion is frame-time based. Wind is a smooth field sampled from position
 * and time, plus a small sway and the slot's own fall speed. The surface cache
 * is a 49 by 49 grid refreshed every ten ticks, never a height query in the
 * draw loop.</p>
 */
public final class AtmosphereParticles {
	private static final int MAX_POOL = 4000;
	private static final int GRID = 49;
	private static final int GRID_HALF = GRID / 2;
	private static final double[] BAND_HX = {4.5, 11.0, 22.0};
	private static final double[] BAND_HY = {3.5, 7.0, 14.0};
	private static final double NEAR_FADE = 1.0;
	private static final float LIGHT_MIN = 0.38f;
	private static final Identifier ATLAS =
			Identifier.fromNamespaceAndPath("capy", "textures/atmosphere/particle_atlas.png");

	private static final double[] px = new double[MAX_POOL];
	private static final double[] py = new double[MAX_POOL];
	private static final double[] pz = new double[MAX_POOL];
	private static final float[] fall = new float[MAX_POOL];
	private static final float[] size = new float[MAX_POOL];
	private static final float[] spin = new float[MAX_POOL];
	private static final float[] spinSpeed = new float[MAX_POOL];
	private static final float[] phase = new float[MAX_POOL];
	private static final int[] variant = new int[MAX_POOL];
	private static final int[] band = new int[MAX_POOL];
	private static final boolean[] settled = new boolean[MAX_POOL];
	private static final float[] settleTime = new float[MAX_POOL];
	private static final boolean[] sways = new boolean[MAX_POOL];

	private static final float[] heights = new float[GRID * GRID];
	private static final boolean[] heightValid = new boolean[GRID * GRID];
	private static Level cacheLevel;
	private static int cacheX;
	private static int cacheZ;
	private static long cacheTick = Long.MIN_VALUE;
	private static long cacheChunk = Long.MIN_VALUE;

	private static boolean filled;
	private static int lastTheme = -1;
	private static int lastQuality = -1;
	private static long lastFrame;
	private static float appearance;
	private static float exposure = 1f;
	private static float skyLight = 1f;
	private static long lastLightTick = Long.MIN_VALUE;
	private static Vec3 lastCenter;

	private AtmosphereParticles() {
	}

	/** Drops state on a world change or disconnect. */
	public static void reset() {
		Arrays.fill(settled, false);
		Arrays.fill(settleTime, 0f);
		Arrays.fill(heightValid, false);
		filled = false;
		lastTheme = -1;
		lastQuality = -1;
		lastFrame = 0L;
		appearance = 0f;
		exposure = 1f;
		skyLight = 1f;
		lastLightTick = Long.MIN_VALUE;
		lastCenter = null;
		cacheLevel = null;
		cacheTick = Long.MIN_VALUE;
		cacheChunk = Long.MIN_VALUE;
	}

	/**
	 * Draws and advances the pool. Called inside the world pass with a
	 * camera-relative pose; every emitted vertex subtracts the camera exactly
	 * once, at emission.
	 */
	public static void render(MultiBufferSource.BufferSource batches, PoseStack.Pose pose,
			Camera camera, float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null || mc.gameRenderer == null) {
			return;
		}
		if (camera.getFluidInCamera() != FogType.NONE) {
			return;
		}
		int theme = AtmosphereState.theme;
		int quality = Math.max(100, Math.min(MAX_POOL, AtmosphereState.particleQuality));
		if (!filled || theme != lastTheme || quality != lastQuality) {
			fill(mc, camera, theme, quality);
		}
		advance(mc, camera, partialTick);

		ThemeProfile profile = ThemeProfile.of(theme);
		int active = Math.round(quality * AtmosphereState.particleDensity * profile.density * exposure);
		active = Math.max(0, Math.min(quality, active));
		if (active == 0 || appearance <= 0.01f) {
			return;
		}
		VertexConsumer vc = batches.getBuffer(RenderTypes.entityTranslucent(ATLAS));
		double cx = camera.position().x;
		double cy = camera.position().y;
		double cz = camera.position().z;
		var forward = camera.forwardVector();
		float light = lightTint(mc, partialTick);
		for (int i = 0; i < active; i++) {
			double dx = px[i] - cx;
			double dy = py[i] - cy;
			double dz = pz[i] - cz;
			double distanceSq = dx * dx + dy * dy + dz * dz;
			if (distanceSq < 1.0 || distanceSq > 26.0 * 26.0) {
				continue;
			}
			double distance = Math.sqrt(distanceSq);
			if (dx * forward.x() + dy * forward.y() + dz * forward.z() < -0.10) {
				continue;
			}
			double hx = BAND_HX[band[i]];
			double hy = BAND_HY[band[i]];
			float alpha = profile.alpha * appearance * edgeFade(dx / hx, dy / hy);
			alpha *= nearFade(distance);
			alpha *= light;
			if (settled[i]) {
				alpha *= Math.max(0f, 1f - settleTime[i] / 1.4f);
			}
			if (alpha <= 0.008f) {
				continue;
			}
			int color = tint(profile.color(i), alpha);
			double width = profile.size * AtmosphereState.particleSize * size[i];
			int sprite = profile.sprite(i);
			if (profile.petal) {
				petal(vc, pose, px[i] - cx, py[i] - cy, pz[i] - cz, width, spin[i], phase[i], color, sprite);
			} else if (profile.streak) {
				streak(vc, pose, dx, dy, dz, width, color, sprite);
			} else {
				billboard(vc, pose, dx, dy, dz, width, color, sprite);
			}
		}
	}

	private static void fill(Minecraft mc, Camera camera, int theme, int quality) {
		Vec3 center = camera.position();
		for (int i = 0; i < quality; i++) {
			int b = i % 10 < 5 ? 0 : (i % 10 < 8 ? 1 : 2);
			band[i] = b;
			px[i] = center.x + signed(hash(i * 17 + 3 + theme * 101)) * BAND_HX[b] * 0.94;
			py[i] = center.y + signed(hash(i * 31 + 11 + theme * 71)) * BAND_HY[b] * 0.94;
			pz[i] = center.z + signed(hash(i * 47 + 5 + theme * 37)) * BAND_HX[b] * 0.94;
			fall[i] = 0.30f + (float) hash(i * 59 + 7) * 0.70f;
			size[i] = 0.55f + (float) hash(i * 71 + 13) * 0.90f;
			spin[i] = (float) (hash(i * 83 + 19) * Math.PI * 2.0);
			spinSpeed[i] = (float) ((hash(i * 97 + 23) - 0.5) * 2.4);
			phase[i] = (float) (hash(i * 109 + 29) * Math.PI * 2.0);
			variant[i] = i % 8;
			settled[i] = false;
			settleTime[i] = 0f;
			sways[i] = hash(i * 113 + 31) > 0.55;
		}
		Arrays.fill(heightValid, false);
		filled = true;
		lastTheme = theme;
		lastQuality = quality;
		lastCenter = center;
		appearance = 0f;
		lastFrame = 0L;
	}

	private static void advance(Minecraft mc, Camera camera, float partialTick) {
		long now = System.nanoTime();
		float dt = lastFrame == 0L ? 1f / 60f : (now - lastFrame) / 1.0e9f;
		lastFrame = now;
		dt = Math.max(0.001f, Math.min(0.1f, dt));
		appearance += (1f - appearance) * (1f - (float) Math.exp(-4.5f * dt));
		if (appearance > 0.999f) {
			appearance = 1f;
		}
		updateExposure(mc);
		ensureHeights(mc);

		double cx = camera.position().x;
		double cy = camera.position().y;
		double cz = camera.position().z;
		if (lastCenter != null) {
			double jump = Math.abs(cx - lastCenter.x) + Math.abs(cy - lastCenter.y)
					+ Math.abs(cz - lastCenter.z);
			if (jump > 64.0) {
				fill(mc, camera, lastTheme, lastQuality);
				lastCenter = camera.position();
				return;
			}
		}
		lastCenter = camera.position();
		float rate = AtmosphereState.particleSpeed;
		float windStrength = AtmosphereState.particleWind;
		double seconds = now / 1.0e9;
		int active = Math.min(lastQuality, Math.max(0,
				Math.round(lastQuality * AtmosphereState.particleDensity * exposure)));
		for (int i = 0; i < lastQuality; i++) {
			if (i >= active) {
				continue;
			}
			double hx = BAND_HX[band[i]];
			double hy = BAND_HY[band[i]];
			float ground = groundAt(px[i], pz[i]);
			boolean hasGround = !Float.isNaN(ground);
			if (settled[i]) {
				settleTime[i] += dt;
				spin[i] += spinSpeed[i] * dt * 0.18f;
				if (settleTime[i] > 1.6f) {
					settled[i] = false;
					settleTime[i] = 0f;
					py[i] = cy + hy * 0.92;
				}
				continue;
			}
			double windX = wind(px[i], py[i], cz + seconds, seconds, 0.061, 0.021)
					+ wind(px[i], py[i], pz[i], seconds, 0.017, 0.009);
			double windZ = wind(pz[i], py[i], px[i], seconds, 0.053, 0.019)
					+ wind(pz[i], py[i], px[i], seconds, 0.023, 0.011);
			double sway = sways[i] ? Math.sin(seconds * 1.7 + phase[i]) * 0.055 : 0.0;
			px[i] += (windX * 0.16 * windStrength + sway) * dt;
			pz[i] += (windZ * 0.16 * windStrength - sway * 0.6) * dt;
			py[i] -= fall[i] * rate * dt;
			spin[i] += spinSpeed[i] * dt;
			phase[i] += dt * 0.23f;

			if (hasGround) {
				if (py[i] < ground - 0.12) {
					py[i] = cy + hy * 0.95;
					settled[i] = false;
					settleTime[i] = 0f;
				} else if (py[i] < ground + 0.12 && i % 6 == 0 && fall[i] > 0.15f) {
					settled[i] = true;
					settleTime[i] = 0f;
					py[i] = ground + 0.035;
				}
			}
			wrap(px, i, cx, hx);
			wrap(pz, i, cz, hx);
			wrapY(py, i, cy, hy);
		}
		lastCenter = camera.position();
	}

	/** Wraps one absolute axis position around the camera's box. */
	public static double wrapInto(double value, double center, double half) {
		double min = center - half;
		double max = center + half;
		double span = max - min;
		while (value < min) {
			value += span;
		}
		while (value > max) {
			value -= span;
		}
		return value;
	}

	/** Alpha multiplier for the one-block no-pop zone around the camera. */
	public static float nearFade(double distance) {
		return smooth(1.0, 2.2, distance);
	}

	private static void wrap(double[] values, int i, double center, double half) {
		values[i] = wrapInto(values[i], center, half);
	}

	private static void wrapY(double[] values, int i, double center, double half) {
		wrap(values, i, center, half);
	}

	private static double wind(double a, double b, double c, double time, double f1, double f2) {
		return Math.sin(a * f1 + time * 0.31) * Math.cos(b * f2 - time * 0.19)
				+ Math.sin(c * f2 + time * 0.13 + 1.7) * 0.55;
	}

	private static float edgeFade(double nx, double ny) {
		double horizontal = 1.0 - smooth(0.70, 1.0, Math.abs(nx));
		double vertical = 1.0 - smooth(0.60, 1.0, Math.abs(ny));
		return (float) Math.max(0.0, Math.min(1.0, horizontal * vertical));
	}

	private static float smooth(double edge0, double edge1, double value) {
		if (edge1 <= edge0) {
			return value < edge0 ? 0f : 1f;
		}
		double t = Math.max(0.0, Math.min(1.0, (value - edge0) / (edge1 - edge0)));
		return (float) (t * t * (3.0 - 2.0 * t));
	}

	private static void updateExposure(Minecraft mc) {
		long tick = mc.level.getGameTime();
		if (lastLightTick != Long.MIN_VALUE && tick - lastLightTick < 10L) {
			return;
		}
		lastLightTick = tick;
		BlockPos pos = mc.player.blockPosition();
		boolean sky = mc.level.canSeeSky(pos);
		int light = mc.level.getMaxLocalRawBrightness(pos);
		float target = sky ? 0.65f + light / 15f * 0.35f : 0.24f;
		exposure += (target - exposure) * 0.20f;
		exposure = Math.max(0.18f, Math.min(1f, exposure));
		skyLight = sky ? 1f : 0.42f;
	}

	private static float lightTint(Minecraft mc, float partialTick) {
		long time = mc.level.getDayTime() % 24000L;
		float day = (float) (0.5 + 0.5 * Math.cos((time - 6000L) / 24000.0 * Math.PI * 2.0));
		float rain = mc.level.getRainLevel(partialTick);
		rain = Math.max(0f, Math.min(1f, rain));
		return Math.max(LIGHT_MIN, skyLight * (0.55f + day * 0.45f) * (1f - rain * 0.18f));
	}

	private static int tint(int color, float alpha) {
		int a = Math.round(((color >>> 24) & 0xFF) * alpha);
		return (Math.max(0, Math.min(255, a)) << 24) | (color & 0x00FFFFFF);
	}

	private static double signed(double value) {
		return value * 2.0 - 1.0;
	}

	private static double hash(int seed) {
		int x = seed * 0x9E3779B9;
		x ^= x >>> 16;
		x *= 0x85EBCA6B;
		x ^= x >>> 13;
		return (x & 0x7FFFFFFF) / (double) 0x7FFFFFFF;
	}

	// ------------------------------------------------------------ surface cache

	private static void ensureHeights(Minecraft mc) {
		Level level = mc.level;
		int bx = mc.player == null ? 0 : mc.player.blockPosition().getX();
		int bz = mc.player == null ? 0 : mc.player.blockPosition().getZ();
		long tick = level.getGameTime();
		long chunk = (((long) (bx >> 4)) << 32) ^ (bz >> 4);
		boolean changed = cacheLevel != level || cacheChunk != chunk
				|| cacheX != bx || cacheZ != bz;
		if (!changed && cacheTick != Long.MIN_VALUE && tick - cacheTick < 10L) {
			return;
		}
		cacheLevel = level;
		cacheX = bx;
		cacheZ = bz;
		cacheTick = tick;
		cacheChunk = chunk;
		for (int dz = -GRID_HALF; dz <= GRID_HALF; dz++) {
			for (int dx = -GRID_HALF; dx <= GRID_HALF; dx++) {
				int x = bx + dx;
				int z = bz + dz;
				int index = (dz + GRID_HALF) * GRID + dx + GRID_HALF;
				if (!level.hasChunkAt(new BlockPos(x, level.getMinY(), z))) {
					heightValid[index] = false;
					continue;
				}
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				heights[index] = y - 0.04f;
				heightValid[index] = true;
			}
		}
	}

	private static float groundAt(double x, double z) {
		if (cacheLevel == null) {
			return Float.NaN;
		}
		int bx = (int) Math.floor(x);
		int bz = (int) Math.floor(z);
		int dx = bx - cacheX + GRID_HALF;
		int dz = bz - cacheZ + GRID_HALF;
		if (dx < 0 || dz < 0 || dx >= GRID || dz >= GRID) {
			return Float.NaN;
		}
		int index = dz * GRID + dx;
		return heightValid[index] ? heights[index] : Float.NaN;
	}

	// --------------------------------------------------------------- geometry

	private static void petal(VertexConsumer vc, PoseStack.Pose pose,
			double x, double y, double z, double width, float angle, float tilt, int color, int sprite) {
		double ca = Math.cos(angle);
		double sa = Math.sin(angle);
		double ct = Math.cos(tilt);
		double st = Math.sin(tilt);
		double ux = ca * width;
		double uy = sa * width * 0.20;
		double uz = sa * width;
		double vx = -sa * st * width * 0.55;
		double vy = ct * width;
		double vz = ca * st * width * 0.55;
		textured(vc, pose, x - ux - vx, y - uy - vy, z - uz - vz, sprite, 0, 0, color);
		textured(vc, pose, x + ux - vx, y + uy - vy, z + uz - vz, sprite, 1, 0, color);
		textured(vc, pose, x + ux + vx, y + uy + vy, z + uz + vz, sprite, 1, 1, color);
		textured(vc, pose, x - ux + vx, y - uy + vy, z - uz + vz, sprite, 0, 1, color);
	}

	private static void billboard(VertexConsumer vc, PoseStack.Pose pose,
			double dx, double dy, double dz, double width, int color, int sprite) {
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (distance < 1.0e-5) {
			return;
		}
		double nx = dx / distance;
		double ny = dy / distance;
		double nz = dz / distance;
		double rx = nz;
		double rz = -nx;
		double rl = Math.sqrt(rx * rx + rz * rz);
		if (rl < 1.0e-5) {
			rx = 1.0;
			rz = 0.0;
			rl = 1.0;
		}
		rx /= rl;
		rz /= rl;
		double ux = ny * rz;
		double uy = nx * rz - nz * rx;
		double uz = -ny * rx;
		double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
		if (ul < 1.0e-5) {
			return;
		}
		ux = ux / ul * width;
		uy = uy / ul * width;
		uz = uz / ul * width;
		rx *= width;
		rz *= width;
		textured(vc, pose, dx - rx - ux, dy - uy, dz - rz - uz, sprite, 0, 0, color);
		textured(vc, pose, dx + rx - ux, dy - uy, dz + rz - uz, sprite, 1, 0, color);
		textured(vc, pose, dx + rx + ux, dy + uy, dz + rz + uz, sprite, 1, 1, color);
		textured(vc, pose, dx - rx + ux, dy + uy, dz - rz + uz, sprite, 0, 1, color);
	}

	private static void streak(VertexConsumer vc, PoseStack.Pose pose,
			double dx, double dy, double dz, double width, int color, int sprite) {
		double length = 0.55;
		textured(vc, pose, dx - width * 0.13, dy - length, dz, sprite, 0, 0, color);
		textured(vc, pose, dx + width * 0.13, dy - length, dz, sprite, 1, 0, color);
		textured(vc, pose, dx + width * 0.13, dy + length, dz, sprite, 1, 1, color);
		textured(vc, pose, dx - width * 0.13, dy + length, dz, sprite, 0, 1, color);
	}

	private static void textured(VertexConsumer vc, PoseStack.Pose pose,
			double x, double y, double z, int sprite, float u, float v, int color) {
		int cellX = sprite & 3;
		int cellY = (sprite >> 2) & 1;
		float u0 = cellX * 0.25f + u * 0.25f;
		float v0 = cellY * 0.5f + v * 0.5f;
		vc.addVertex(pose, (float) x, (float) y, (float) z)
				.setColor(color)
				.setUv(u0, v0)
				.setOverlay(0)
				.setLight(LightTexture.FULL_BRIGHT)
				.setNormal(0f, 1f, 0f);
	}

	/** One theme's sprite, palette and motion. */
	private record ThemeProfile(int[] colors, int spriteStart, int spriteCount,
			float density, float size, float alpha, boolean petal, boolean streak, boolean bubble) {
		private static final ThemeProfile[] PROFILES = {
				new ThemeProfile(new int[]{0xFFD8E8FF, 0xFFEFF6FF, 0xFFFFF0D8}, 0, 1, 0.55f, 0.020f, 0.42f, false, false, false),
				new ThemeProfile(new int[]{0xFFFFC47A, 0xFFFFE0A8, 0xFFFFF1C8}, 0, 1, 0.75f, 0.026f, 0.50f, false, false, false),
				new ThemeProfile(new int[]{0xFF9CC8FF, 0xFFD8E8FF, 0xFF7898D8}, 7, 1, 0.55f, 0.018f, 0.46f, false, false, false),
				new ThemeProfile(new int[]{0xFF5CFFE0, 0xFFB8FFF2, 0xFF8AB4FF}, 0, 1, 0.62f, 0.028f, 0.40f, false, false, false),
				new ThemeProfile(new int[]{0xFFE8F4FF, 0xFFC8E0F8, 0xFFFFF4FA}, 5, 2, 0.92f, 0.020f, 0.55f, false, true, false),
				new ThemeProfile(new int[]{0xFFB8F4FF, 0xFF8AD8F8, 0xFFD8FFF4}, 6, 2, 0.72f, 0.026f, 0.48f, false, false, true),
				new ThemeProfile(new int[]{0xFFFFD08A, 0xFFFF5A2A, 0xFF9C3A28}, 7, 1, 0.70f, 0.024f, 0.48f, false, false, false),
				new ThemeProfile(new int[]{0xFFFFF4FB, 0xFFFFD6EA, 0xFFD6FFF8, 0xFFFFC8DE}, 1, 3, 1.00f, 0.042f, 0.62f, true, false, false),
				new ThemeProfile(new int[]{0xFFC46AFF, 0xFF7A3CD8, 0xFF2A1348}, 7, 1, 0.48f, 0.020f, 0.40f, false, false, false),
				new ThemeProfile(new int[]{0xFFFF56D8, 0xFF3FF4E4, 0xFFB8FFF4}, 7, 1, 0.65f, 0.022f, 0.50f, false, false, false)
		};

		static ThemeProfile of(int theme) {
			return PROFILES[Math.max(0, Math.min(PROFILES.length - 1, theme))];
		}

		int color(int slot) {
			return colors[Math.abs(slot) % colors.length];
		}

		int sprite(int slot) {
			return spriteStart + Math.abs(slot * 7) % spriteCount;
		}
	}
}
