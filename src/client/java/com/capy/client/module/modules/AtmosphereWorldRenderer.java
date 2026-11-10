package com.capy.client.module.modules;

import com.capy.client.util.Guard;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * The geometry part of Atmosphere: the Neon lattice, aurora curtains, the
 * distant ocean whale and the Void rift.
 *
 * <p>Every ordinary particle in every theme is drawn by
 * {@link AtmosphereParticles}; this class only owns shapes that a single quad
 * pool cannot make. All positions here are camera-relative when emitted. Neon's
 * lattice is anchored to exact world coordinates and converted at the vertex,
 * which is why it stays on the ground while the camera flies.</p>
 */
public final class AtmosphereWorldRenderer {
	private static final int BUFFER_BYTES = 2 * 1024 * 1024;
	private static final int NEON_SPACING = 3;
	private static final int NEON_REACH = 24;
	private static final int NEON_STEP = 2;
	private static final int NEON_MAX_SEGMENTS = 1400;
	private static final int NEON_GRID = 49;
	private static final int NEON_HALF = NEON_GRID / 2;
	private static MultiBufferSource.BufferSource batch;
	private static ByteBufferBuilder buffer;
	private static final Map<Long, Integer> NEON_HEIGHTS = new HashMap<>();
	private static long neonRefreshTick = -1L;
	private static Object neonLevel;
	private static long neonChunk = Long.MIN_VALUE;
	private static Vec3 neonCenter;

	private AtmosphereWorldRenderer() {
	}

	/** Allocated once for the unusual context without a matrix. */
	private static final class FallbackStack {
		private static final PoseStack INSTANCE = new PoseStack();
	}

	/** Drops cached heights and the batch on a world change or disconnect. */
	public static void reset() {
		NEON_HEIGHTS.clear();
		neonRefreshTick = -1L;
		neonLevel = null;
		neonChunk = Long.MIN_VALUE;
		neonCenter = null;
		AtmosphereParticles.reset();
		discard();
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
			AtmosphereState.advance();
			Camera camera = mc.gameRenderer.getMainCamera();
			Vec3 eye = camera.position();
			PoseStack stack = context.matrices();
			if (stack == null) {
				stack = FallbackStack.INSTANCE;
			}
			PoseStack.Pose pose = stack.last();
			MultiBufferSource.BufferSource batches = batch();
			VertexConsumer vc = batches.getBuffer(RenderTypes.debugQuads());

			float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
			double px = mc.player.getX(partial);
			double py = mc.player.getY(partial);
			double pz = mc.player.getZ(partial);
			double ox = px - eye.x;
			double oy = py - eye.y;
			double oz = pz - eye.z;
			double now = System.nanoTime() / 1.0e9;

			switch (AtmosphereState.theme) {
				case 3 -> aurora(vc, pose, ox, oy, oz, now);
				case 5 -> ocean(vc, pose, ox, oy, oz, now);
				case 8 -> voidRift(vc, pose, ox, oy, oz, now);
				case 9 -> neon(vc, pose, ox, oy, oz, eye, now);
				default -> {
				}
			}
			AtmosphereParticles.render(batches, pose, camera, partial);
			batches.endBatch();
		} catch (RuntimeException | LinkageError failure) {
			discard();
			Guard.report("atmosphere world", failure);
		}
	}

	// ------------------------------------------------------------- geometry

	/** Curtains of light: a few tall, segmented planes with a vertical fade. */
	private static void aurora(VertexConsumer vc, PoseStack.Pose pose,
			double ox, double oy, double oz, double now) {
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
				if (i % 3 == 0) {
					high = 0x708A9BFF;
				}
				quad(vc, pose, x0, base, z0, x1, base, z1, x1, top, z1, x0, top, z0, low, high);
			}
		}
	}

	/** A far, slow whale silhouette: three soft dark ovals crossing the water. */
	private static void ocean(VertexConsumer vc, PoseStack.Pose pose,
			double ox, double oy, double oz, double now) {
		double travel = Math.sin(now * 0.018) * 26.0;
		double x = ox + 30.0 + travel;
		double z = oz + 24.0;
		double y = oy + 7.0;
		int body = 0x38234A68;
		fan(vc, pose, x, y, z, 7.0, body, 1, 0, 0, 0, 1, 0);
		fan(vc, pose, x + 7.0, y + 1.4, z, 3.0, body, 1, 0, 0, 0, 1, 0);
		fan(vc, pose, x + 8.0, y + 3.8, z, 1.8, body, 1, 0, 0, 0, 1, 0);
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
	}

	/**
	 * Neon's ground lattice. The stored coordinates are absolute multiples of
	 * {@value #NEON_SPACING}; the camera is subtracted at each vertex, never
	 * before the grid is built.
	 */
	private static void neon(VertexConsumer vc, PoseStack.Pose pose,
			double ox, double oy, double oz, Vec3 camera, double now) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		refreshNeonHeights(mc, camera);
		int startX = Math.floorDiv((int) Math.floor(camera.x) - NEON_REACH, NEON_STEP) * NEON_STEP;
		int startZ = Math.floorDiv((int) Math.floor(camera.z) - NEON_REACH, NEON_STEP) * NEON_STEP;
		int minLineX = (int) Math.floor((camera.x - NEON_REACH) / NEON_SPACING);
		int maxLineX = (int) Math.floor((camera.x + NEON_REACH) / NEON_SPACING);
		int minLineZ = (int) Math.floor((camera.z - NEON_REACH) / NEON_SPACING);
		int maxLineZ = (int) Math.floor((camera.z + NEON_REACH) / NEON_SPACING);
		int drawn = 0;

		for (int ix = minLineX; ix <= maxLineX && drawn < NEON_MAX_SEGMENTS; ix++) {
			double x = AtmosphereMath.neonLineCoordinate(ix, NEON_SPACING);
			for (int z = startZ; z <= camera.z + NEON_REACH && drawn < NEON_MAX_SEGMENTS; z += NEON_STEP) {
				Integer h0 = neonHeight(mc, x, z);
				Integer h1 = neonHeight(mc, x, z + NEON_STEP);
				if (h0 == null || h1 == null) {
					continue;
				}
				int color = neonColor(x, z, camera, now, (ix & 1) == 0);
				if ((color >>> 24) < 5) {
					continue;
				}
				double sx = AtmosphereMath.cameraRelative(x, camera.x);
				double sz = AtmosphereMath.cameraRelative(z, camera.z);
				quad(vc, pose, sx - 0.025, h0 + 0.03, sz, sx + 0.025, h0 + 0.03, sz,
						sx + 0.025, h1 + 0.03, sz + NEON_STEP, sx - 0.025, h1 + 0.03, sz + NEON_STEP,
						color, color);
				drawn++;
			}
		}
		for (int iz = minLineZ; iz <= maxLineZ && drawn < NEON_MAX_SEGMENTS; iz++) {
			double z = AtmosphereMath.neonLineCoordinate(iz, NEON_SPACING);
			for (int x = startX; x <= camera.x + NEON_REACH && drawn < NEON_MAX_SEGMENTS; x += NEON_STEP) {
				Integer h0 = neonHeight(mc, x, z);
				Integer h1 = neonHeight(mc, x + NEON_STEP, z);
				if (h0 == null || h1 == null) {
					continue;
				}
				int color = neonColor(x, z, camera, now, (iz & 1) == 0);
				if ((color >>> 24) < 5) {
					continue;
				}
				double sx = AtmosphereMath.cameraRelative(x, camera.x);
				double sz = AtmosphereMath.cameraRelative(z, camera.z);
				quad(vc, pose, sx, h0 + 0.03, sz - 0.025, sx, h0 + 0.03, sz + 0.025,
						sx + NEON_STEP, h1 + 0.03, sz + 0.025, sx + NEON_STEP, h1 + 0.03, sz - 0.025,
						color, color);
				drawn++;
			}
		}
	}

	private static void refreshNeonHeights(Minecraft mc, Vec3 camera) {
		long tick = mc.level.getGameTime();
		int bx = (int) Math.floor(camera.x);
		int bz = (int) Math.floor(camera.z);
		long chunk = (((long) (bx >> 4)) << 32) ^ (bz >> 4);
		boolean changed = neonLevel != mc.level || neonChunk != chunk
				|| neonCenter == null || Math.abs(camera.x - neonCenter.x) >= NEON_SPACING
				|| Math.abs(camera.z - neonCenter.z) >= NEON_SPACING;
		if (!AtmosphereMath.heightCacheNeedsRefresh(tick, neonRefreshTick, changed)) {
			return;
		}
		neonLevel = mc.level;
		neonChunk = chunk;
		neonRefreshTick = tick;
		neonCenter = camera;
		NEON_HEIGHTS.clear();
		for (int dz = -NEON_HALF; dz <= NEON_HALF; dz++) {
			for (int dx = -NEON_HALF; dx <= NEON_HALF; dx++) {
				int x = bx + dx;
				int z = bz + dz;
				if (!mc.level.hasChunkAt(new net.minecraft.core.BlockPos(x, mc.level.getMinY(), z))) {
					continue;
				}
				int y = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				NEON_HEIGHTS.put(key(x, z), y);
			}
		}
	}

	private static Integer neonHeight(Minecraft mc, double x, double z) {
		return NEON_HEIGHTS.get(key((int) Math.floor(x), (int) Math.floor(z)));
	}

	private static long key(int x, int z) {
		return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
	}

	private static int neonColor(double x, double z, Vec3 camera, double now, boolean cyan) {
		double dx = x - camera.x;
		double dz = z - camera.z;
		double distance = Math.sqrt(dx * dx + dz * dz);
		float fade = 1f - (float) (distance / NEON_REACH);
		if (fade <= 0f) {
			return 0;
		}
		double pulse = 0.66 + 0.34 * Math.sin(now * 2.2 + x * 0.41 + z * 0.57);
		int base = cyan ? 0x6A25F4E0 : 0x5AFF3FC8;
		return withAlpha(base, (int) (((base >>> 24) & 0xFF) * fade * pulse));
	}

	// ---------------------------------------------------------- primitives

	/** A radial fan with a soft rim, emitted as QUADS. */
	private static void fan(VertexConsumer vc, PoseStack.Pose pose, double x, double y, double z,
			double size, int color, double ax, double ay, double az,
			double bx, double by, double bz) {
		int rim = 24;
		int edge = withAlpha(color, 0);
		for (int i = 0; i < rim; i++) {
			double a0 = Math.PI * 2.0 * i / rim;
			double a1 = Math.PI * 2.0 * (i + 1) / rim;
			double c0 = Math.cos(a0) * size;
			double s0 = Math.sin(a0) * size;
			double c1 = Math.cos(a1) * size;
			double s1 = Math.sin(a1) * size;
			vertex(vc, pose, x, y, z, color);
			vertex(vc, pose, x, y, z, color);
			vertex(vc, pose, x + ax * c0 + bx * s0, y + ay * c0 + by * s0,
					z + az * c0 + bz * s0, edge);
			vertex(vc, pose, x + ax * c1 + bx * s1, y + ay * c1 + by * s1,
					z + az * c1 + bz * s1, edge);
		}
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
			} catch (RuntimeException | LinkageError failure) {
				Guard.report("atmosphere world flush", failure);
			}
			batch = null;
		}
		if (buffer != null) {
			try {
				buffer.close();
			} catch (RuntimeException | LinkageError failure) {
				Guard.report("atmosphere world release", failure);
			}
			buffer = null;
		}
	}
}
