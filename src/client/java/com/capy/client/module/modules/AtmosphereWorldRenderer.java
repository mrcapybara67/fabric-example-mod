package com.capy.client.module.modules;

import com.capy.client.util.Guard;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * The part of Atmosphere that is geometry instead of a colour.
 *
 * <h2>Why this exists</h2>
 * Particle lists alone cannot make an aurora: vanilla has no ribbon that hangs
 * in the sky, no petal with two crossed faces, and no light shaft. Those are
 * custom quads, so they are submitted here, in the world pass, with the
 * camera's matrix and camera-relative vertices. They are not painted on the
 * HUD and they do not replace the sky; they sit in the same depth buffer as the
 * terrain, which is what lets a petal pass behind a tree.
 *
 * <h2>Budget</h2>
 * This runs on phones, so every theme has a hard, small object count and every
 * object is one or two quads. All positions are computed from a cheap hash and
 * the frame time; nothing is allocated inside the loops, and the buffer is
 * reused between frames. Sakura is 72 textured petals, two crossed faces each:
 * 1,152 vertices, not 72 models.
 *
 * <h2>Theme ownership</h2>
 * {@link AtmosphereState#theme} is written by the module on the tick. The
 * renderer never reads the settings object, so a theme change is a single
 * integer read and cannot race the GUI.
 */
public final class AtmosphereWorldRenderer {
	private static final int BUFFER_BYTES = 2 * 1024 * 1024;
	/**
	 * The opal petal is the one theme sprite drawn from a real texture. It is
	 * not a vanilla particle: the shape, the iridescent veins and the pearl
	 * flecks all belong to the client, and the texture is sampled on both
	 * crossed faces of every falling petal.
	 */
	private static final Identifier OPAL_PETAL =
			Identifier.fromNamespaceAndPath("capy", "textures/atmosphere/opal_petal.png");
	private static MultiBufferSource.BufferSource batch;
	private static ByteBufferBuilder buffer;

	private AtmosphereWorldRenderer() {
	}

	public static void render(WorldRenderContext context) {
		if (!AtmosphereState.active) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return;
		}
		try {
			Vec3 camera = mc.gameRenderer.getMainCamera().position();
			PoseStack stack = context.matrices();
			if (stack == null) {
				stack = new PoseStack();
			}
			PoseStack.Pose pose = stack.last();
			MultiBufferSource.BufferSource batches = batch();
			VertexConsumer vc = batches.getBuffer(RenderTypes.debugQuads());

			double px = mc.player.getX();
			double py = mc.player.getY();
			double pz = mc.player.getZ();
			double ox = px - camera.x;
			double oy = py - camera.y;
			double oz = pz - camera.z;
			double now = (System.nanoTime() % 60_000_000_000L) / 1.0e9;

			switch (AtmosphereState.theme) {
				case 1 -> dust(vc, pose, ox, oy, oz, now, 0xFFFFC47A, 0xFFFFE0A8, 54);
				case 2 -> midnight(vc, pose, ox, oy, oz, now);
				case 3 -> aurora(vc, pose, ox, oy, oz, now);
				case 4 -> rain(vc, pose, ox, oy, oz, now);
				case 5 -> ocean(vc, pose, ox, oy, oz, now);
				case 6 -> embers(vc, pose, ox, oy, oz, now);
				case 7 -> sakura(batches, pose, ox, oy, oz, now);
				case 8 -> voidRift(vc, pose, ox, oy, oz, now);
				case 9 -> neon(vc, pose, ox, oy, oz, now);
				default -> dust(vc, pose, ox, oy, oz, now, 0xFFBFE0FF, 0xFFFFFFFF, 38);
			}
			batches.endBatch();
		} catch (RuntimeException | LinkageError failure) {
			discard();
			Guard.report("atmosphere world", failure);
		}
	}

	// ------------------------------------------------------------- themes

	/** Curtains of light: a few tall, segmented planes with a vertical fade. */
	private static void aurora(VertexConsumer vc, PoseStack.Pose pose,
			double ox, double oy, double oz, double now) {
		// Four planes, not one: a single ribbon is edge-on from half the world.
		// Each plane is a different distance and a different phase, so walking
		// through the ring changes which ribbon is in front.
		for (int plane = 0; plane < 4; plane++) {
			double angle = plane * Math.PI * 0.5 + 0.35;
			double radius = 72 + plane * 11;
			double ca = Math.cos(angle);
			double sa = Math.sin(angle);
			int segments = 11;
			for (int i = 0; i < segments; i++) {
				double t0 = (double) i / segments;
				double t1 = (double) (i + 1) / segments;
				double x0 = ox + ca * radius - sa * (t0 - 0.5) * 110;
				double z0 = oz + sa * radius + ca * (t0 - 0.5) * 110;
				double x1 = ox + ca * radius - sa * (t1 - 0.5) * 110;
				double z1 = oz + sa * radius + ca * (t1 - 0.5) * 110;
				double wave0 = Math.sin(now * 0.45 + t0 * 7 + plane) * 2.5;
				double wave1 = Math.sin(now * 0.45 + t1 * 7 + plane) * 2.5;
				double base = oy + 36 + wave0;
				double top = oy + 86 + wave1;
				int low = 0x001E9C8E;
				int high = 0x7A65FFCE;
				// Alternating violet edge, so the curtain is not one flat sheet.
				if (i % 3 == 0) {
					high = 0x708A9BFF;
				}
				quad(vc, pose, x0, base, z0, x1, base, z1, x1, top, z1, x0, top, z0, low, high);
			}
		}
	}

	/**
	 * Opal leaves with a real texture on two crossed faces. The texture is the
	 * petal's silhouette, so the quads do not need to fake one with colour
	 * strips; the two crossed copies give the leaf thickness and let its
	 * iridescence catch the light as it turns.
	 */
	private static void sakura(MultiBufferSource.BufferSource batches, PoseStack.Pose pose,
			double ox, double oy, double oz, double now) {
		VertexConsumer vc = batches.getBuffer(RenderTypes.entityTranslucent(OPAL_PETAL));
		int petals = 72;
		for (int i = 0; i < petals; i++) {
			double seed = hash(i * 17 + 3);
			double seed2 = hash(i * 31 + 11);
			double seed3 = hash(i * 47 + 5);
			double size = 0.10 + seed3 * 0.12;
			double speed = 0.42 + seed2 * 0.62;
			double fall = 15.0;
			double cycle = (now * speed + seed * fall) % fall;
			double x = ox + (seed - 0.5) * 26 + Math.sin(now * 0.8 + i * 0.7) * 1.8;
			double y = oy + 12.5 - cycle + Math.sin(now * 1.3 + i) * 0.35;
			double z = oz + (seed2 - 0.5) * 26 + Math.cos(now * 0.65 + i * 0.9) * 1.5;
			double angle = Math.atan2(Math.cos(now * 0.7 + i * 1.1), Math.sin(now * 0.7 + i * 1.1));
			double ux = Math.cos(angle) * size;
			double uz = Math.sin(angle) * size;
			double vx = -uz * 1.45;
			double vz = ux * 1.45;
			double vy = size * 0.62;
			int tint = (i % 3) == 0 ? 0xFFFFF4FB
					: (i % 3) == 1 ? 0xFFFFD6EA : 0xFFD6FFF8;
			texturedPetal(vc, pose, x, y, z, ux, 0, uz, vx, vy, vz, tint);
			texturedPetal(vc, pose, x, y, z, vx, vy, vz, -ux, 0, -uz,
					withAlpha(tint, 205));
		}
	}

	/** Deep Ocean: light shafts, then rising bubbles. */
	private static void ocean(VertexConsumer vc, PoseStack.Pose pose,
			double ox, double oy, double oz, double now) {
		for (int i = 0; i < 7; i++) {
			double seed = hash(i * 23 + 7);
			double angle = seed * Math.PI * 2;
			double radius = 10 + seed * 28;
			double x = ox + Math.cos(angle) * radius;
			double z = oz + Math.sin(angle) * radius;
			double width = 1.4 + hash(i * 9) * 2.8;
			double sway = Math.sin(now * 0.25 + i) * 2.0;
			double top = oy + 24 + hash(i * 5) * 12;
			double bottom = oy - 4;
			int low = 0x1548B8E8;
			int high = 0x4A9BEAFF;
			quad(vc, pose, x - width, bottom, z, x + width, bottom, z,
					x + width + sway, top, z - sway, x - width + sway, top, z - sway, low, high);
		}
		for (int i = 0; i < 34; i++) {
			double seed = hash(i * 41 + 1);
			double seed2 = hash(i * 13 + 9);
			double rise = (now * (0.6 + seed2) + seed * 12) % 12;
			double x = ox + (seed - 0.5) * 16 + Math.sin(now + i) * 0.3;
			double y = oy - 5 + rise;
			double z = oz + (seed2 - 0.5) * 16;
			double r = 0.035 + seed2 * 0.045;
			mote(vc, pose, x, y, z, r, 0xB0B8F4FF);
		}
	}

	/** Crimson: embers that rise and swing, with a coal-red core. */
	private static void embers(VertexConsumer vc, PoseStack.Pose pose,
			double ox, double oy, double oz, double now) {
		for (int i = 0; i < 60; i++) {
			double seed = hash(i * 29 + 4);
			double seed2 = hash(i * 37 + 13);
			double rise = (now * (0.35 + seed2 * 0.8) + seed * 9) % 9;
			double x = ox + (seed - 0.5) * 18 + Math.sin(now * 1.4 + i) * 0.6;
			double y = oy - 1 + rise;
			double z = oz + (seed2 - 0.5) * 18 + Math.cos(now * 1.1 + i) * 0.6;
			double r = 0.045 + seed * 0.05;
			mote(vc, pose, x, y, z, r, i % 4 == 0 ? 0xFFFFD08A : 0xD8FF5A2A);
		}
	}

	/** Neon: a glowing ground grid and drifting circuit dust. */
	private static void neon(VertexConsumer vc, PoseStack.Pose pose,
			double ox, double oy, double oz, double now) {
		double ground = oy - 1.2;
		double spacing = 3.0;
		double reach = 21.0;
		int lines = (int) (reach / spacing);
		for (int i = -lines; i <= lines; i++) {
			double x = ox + i * spacing;
			double z = oz + i * spacing;
			int color = (i & 1) == 0 ? 0x6A25F4E0 : 0x5AFF3FC8;
			quad(vc, pose, x - 0.025, ground, oz - reach, x + 0.025, ground, oz - reach,
					x + 0.025, ground, oz + reach, x - 0.025, ground, oz + reach, color, color);
			quad(vc, pose, ox - reach, ground, z - 0.025, ox + reach, ground, z - 0.025,
					ox + reach, ground, z + 0.025, ox - reach, ground, z + 0.025, color, color);
		}
		for (int i = 0; i < 42; i++) {
			double seed = hash(i * 19 + 8);
			double seed2 = hash(i * 43 + 2);
			double x = ox + (seed - 0.5) * 20;
			double y = oy + 0.2 + (now * (0.25 + seed2 * 0.5) + seed * 8) % 8;
			double z = oz + (seed2 - 0.5) * 20;
			mote(vc, pose, x, y, z, 0.035 + seed * 0.035, i % 3 == 0 ? 0xFFFF56D8 : 0xFF3FF4E4);
		}
	}

	/** Void: slow shards and a distant vertical rift. */
	private static void voidRift(VertexConsumer vc, PoseStack.Pose pose,
			double ox, double oy, double oz, double now) {
		double angle = 0.8;
		double x = ox + Math.cos(angle) * 34;
		double z = oz + Math.sin(angle) * 34;
		double width = 7.0 + Math.sin(now * 0.3) * 1.2;
		for (int i = 0; i < 7; i++) {
			double t = (double) i / 7;
			double y0 = oy + 2 + t * 26;
			double y1 = y0 + 5;
			double jitter = Math.sin(now * 0.6 + i * 2) * 1.4;
			quad(vc, pose, x - width + jitter, y0, z, x + width + jitter, y0 + 1, z,
					x + width * 0.6 + jitter, y1, z, x - width * 0.6 + jitter, y1, z,
					0x203A1A5A, 0x704E2C8A);
		}
		for (int i = 0; i < 38; i++) {
			double seed = hash(i * 31 + 17);
			double seed2 = hash(i * 53 + 6);
			double x2 = ox + (seed - 0.5) * 22 + Math.sin(now * 0.5 + i) * 1.2;
			double y2 = oy + (seed2 - 0.3) * 12 + Math.sin(now * 0.7 + i * 1.7) * 1.5;
			double z2 = oz + (seed2 - 0.5) * 22;
			double r = 0.05 + seed * 0.07;
			mote(vc, pose, x2, y2, z2, r, i % 5 == 0 ? 0xB0C46AFF : 0x702A1348);
		}
	}

	/** Overcast: slanted, close rain that reads even when vanilla weather is clear. */
	private static void rain(VertexConsumer vc, PoseStack.Pose pose,
			double ox, double oy, double oz, double now) {
		for (int i = 0; i < 88; i++) {
			double seed = hash(i * 23 + 3);
			double seed2 = hash(i * 11 + 7);
			double fall = (now * (10 + seed2 * 7) + seed * 20) % 20;
			double x = ox + (seed - 0.5) * 24;
			double y = oy + 13 - fall;
			double z = oz + (seed2 - 0.5) * 24;
			double drift = 0.35;
			quad(vc, pose, x, y, z, x + 0.025, y, z, x + 0.025 + drift, y + 1.1, z + drift * 0.4,
					x + drift, y + 1.1, z + drift * 0.4, 0x406E8CA8, 0x807894A8);
		}
	}

	/** Midnight: a dust of cool stars and a few brighter planets. */
	private static void midnight(VertexConsumer vc, PoseStack.Pose pose,
			double ox, double oy, double oz, double now) {
		for (int i = 0; i < 72; i++) {
			double seed = hash(i * 17 + 12);
			double seed2 = hash(i * 29 + 4);
			double x = ox + (seed - 0.5) * 36;
			double y = oy + 4 + seed2 * 24 + Math.sin(now * 0.25 + i) * 0.4;
			double z = oz + (seed2 - 0.5) * 36;
			double size = i % 9 == 0 ? 0.11 : 0.035;
			mote(vc, pose, x, y, z, size, i % 9 == 0 ? 0xE0C8E8FF : 0x90B8D4FF);
		}
	}

	private static void dust(VertexConsumer vc, PoseStack.Pose pose,
			double ox, double oy, double oz, double now, int warm, int pale, int count) {
		for (int i = 0; i < count; i++) {
			double seed = hash(i * 37 + 5);
			double seed2 = hash(i * 19 + 2);
			double x = ox + (seed - 0.5) * 22 + Math.sin(now * 0.35 + i) * 1.3;
			double y = oy + 0.5 + (seed2 + now * 0.04 + i * 0.13) % 11;
			double z = oz + (seed2 - 0.5) * 22 + Math.cos(now * 0.3 + i) * 1.3;
			double size = 0.028 + seed * 0.045;
			mote(vc, pose, x, y, z, size, i % 3 == 0 ? pale : warm);
		}
	}

	// ----------------------------------------------------------- geometry

	/**
	 * One textured petal face, followed by its reverse winding so the leaf is
	 * visible from above and below. The texture supplies the shape; the tint
	 * supplies the cool pearl shift between individual leaves.
	 */
	private static void texturedPetal(VertexConsumer vc, PoseStack.Pose pose,
			double x, double y, double z,
			double ux, double uy, double uz, double vx, double vy, double vz, int tint) {
		double topX = x + vx;
		double topY = y + vy;
		double topZ = z + vz;
		double botX = x - vx * 0.9;
		double botY = y - vy * 0.9;
		double botZ = z - vz * 0.9;
		texturedVertex(vc, pose, x - ux, y - uy, z - uz, 0f, 0.5f, tint);
		texturedVertex(vc, pose, topX, topY, topZ, 0.5f, 0f, tint);
		texturedVertex(vc, pose, x + ux, y + uy, z + uz, 1f, 0.5f, tint);
		texturedVertex(vc, pose, botX, botY, botZ, 0.5f, 1f, tint);
		// Reverse winding, same UVs: a leaf has two sides.
		texturedVertex(vc, pose, botX, botY, botZ, 0.5f, 1f, tint);
		texturedVertex(vc, pose, x + ux, y + uy, z + uz, 1f, 0.5f, tint);
		texturedVertex(vc, pose, topX, topY, topZ, 0.5f, 0f, tint);
		texturedVertex(vc, pose, x - ux, y - uy, z - uz, 0f, 0.5f, tint);
	}

	private static void texturedVertex(VertexConsumer vc, PoseStack.Pose pose,
			double x, double y, double z, float u, float v, int argb) {
		vc.addVertex(pose, (float) x, (float) y, (float) z)
				.setColor(argb)
				.setUv(u, v)
				.setOverlay(0)
				.setLight(LightTexture.FULL_BRIGHT)
				.setNormal(0f, 1f, 0f);
	}

	/** A small flat billboard-ish spark; two crossed quads would be too many here. */
	private static void mote(VertexConsumer vc, PoseStack.Pose pose,
			double x, double y, double z, double size, int color) {
		quad(vc, pose, x - size, y - size, z, x + size, y - size, z,
				x + size, y + size, z, x - size, y + size, z, color, color);
		quad(vc, pose, x, y - size, z - size, x, y - size, z + size,
				x, y + size, z + size, x, y + size, z - size, color, color);
	}

	private static void quad(VertexConsumer vc, PoseStack.Pose pose,
			double x1, double y1, double z1, double x2, double y2, double z2,
			double x3, double y3, double z3, double x4, double y4, double z4,
			int low, int high) {
		vertex(vc, pose, x1, y1, z1, low);
		vertex(vc, pose, x2, y2, z2, low);
		vertex(vc, pose, x3, y3, z3, high);
		vertex(vc, pose, x4, y4, z4, high);
	}

	private static void vertex(VertexConsumer vc, PoseStack.Pose pose,
			double x, double y, double z, int argb) {
		vc.addVertex(pose, (float) x, (float) y, (float) z).setColor(argb);
	}

	private static int withAlpha(int color, int alpha) {
		return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
	}

	/** Stable [0,1) hash. No Random allocation, no shared state. */
	private static double hash(int seed) {
		int x = seed * 0x9E3779B9;
		x ^= x >>> 16;
		x *= 0x85EBCA6B;
		x ^= x >>> 13;
		return (x & 0x7FFFFFFF) / (double) 0x7FFFFFFF;
	}

	private static MultiBufferSource.BufferSource batch() {
		if (batch == null) {
			buffer = new ByteBufferBuilder(BUFFER_BYTES);
			batch = MultiBufferSource.immediate(buffer);
		}
		return batch;
	}

	private static void discard() {
		if (batch != null) {
			try {
				batch.endBatch();
			} catch (RuntimeException | LinkageError ignored) {
				// The source is unusable either way.
			}
			batch = null;
		}
		if (buffer != null) {
			try {
				buffer.close();
			} catch (RuntimeException | LinkageError ignored) {
				// Nothing left to release.
			}
			buffer = null;
		}
	}
}
