package com.capy.client.module.modules.esp;

import com.capy.client.finder.core.DrawSink;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Turns world-space finder styles into the camera-relative vertices expected
 * by the world pass.
 *
 * <p>The adapter is deliberately the only place that knows about camera
 * subtraction and frustum culling. Styles can therefore stay in chunk/world
 * coordinates and remain testable without a running client. A quad is emitted
 * as exactly four vertices and a line as exactly two; nothing is submitted as a
 * triangle strip or a fan.</p>
 */
public final class SinkAdapter implements DrawSink {
	/** The low-level writer. Tests substitute a recorder; production writes vertices. */
	interface Target {
		void quad(double x0, double y0, double z0, double x1, double y1, double z1,
				double x2, double y2, double z2, double x3, double y3, double z3, int argb);

		void line(double x0, double y0, double z0, double x1, double y1, double z1,
				float width, int argb);
	}

	private final Target target;
	private final double cameraX;
	private final double cameraY;
	private final double cameraZ;
	private final Frustum frustum;
	private int quadVertices;
	private int lineVertices;

	/** Production constructor used by {@link ChunkRenderPass}. */
	public SinkAdapter(PoseStack.Pose pose, VertexConsumer quads, VertexConsumer lines,
			Vec3 camera, Frustum frustum, float lineScale) {
		this(new VertexTarget(pose, quads, lines, lineScale), camera, frustum);
	}

	/** Test seam: no Minecraft renderer is needed. */
	SinkAdapter(Target target, Vec3 camera, Frustum frustum) {
		this.target = target;
		this.cameraX = camera.x;
		this.cameraY = camera.y;
		this.cameraZ = camera.z;
		this.frustum = frustum;
	}

	@Override
	public void quad(double x0, double y0, double z0, double x1, double y1, double z1,
			double x2, double y2, double z2, double x3, double y3, double z3, int argb) {
		if (!visible(x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3)) {
			return;
		}
		target.quad(x0 - cameraX, y0 - cameraY, z0 - cameraZ,
				x1 - cameraX, y1 - cameraY, z1 - cameraZ,
				x2 - cameraX, y2 - cameraY, z2 - cameraZ,
				x3 - cameraX, y3 - cameraY, z3 - cameraZ, argb);
		quadVertices += 4;
	}

	@Override
	public void line(double x0, double y0, double z0, double x1, double y1, double z1,
			float width, int argb) {
		if (!visible(x0, y0, z0, x1, y1, z1)) {
			return;
		}
		target.line(x0 - cameraX, y0 - cameraY, z0 - cameraZ,
				x1 - cameraX, y1 - cameraY, z1 - cameraZ, width, argb);
		lineVertices += 2;
	}

	int quadVertices() {
		return quadVertices;
	}

	int lineVertices() {
		return lineVertices;
	}

	private boolean visible(double... points) {
		if (frustum == null) {
			return true;
		}
		double minX = Double.POSITIVE_INFINITY;
		double minY = Double.POSITIVE_INFINITY;
		double minZ = Double.POSITIVE_INFINITY;
		double maxX = Double.NEGATIVE_INFINITY;
		double maxY = Double.NEGATIVE_INFINITY;
		double maxZ = Double.NEGATIVE_INFINITY;
		for (int i = 0; i + 2 < points.length; i += 3) {
			minX = Math.min(minX, points[i]);
			minY = Math.min(minY, points[i + 1]);
			minZ = Math.min(minZ, points[i + 2]);
			maxX = Math.max(maxX, points[i]);
			maxY = Math.max(maxY, points[i + 1]);
			maxZ = Math.max(maxZ, points[i + 2]);
		}
		return frustum.isVisible(new AABB(minX, minY, minZ, maxX, maxY, maxZ));
	}

	private static final class VertexTarget implements Target {
		private final PoseStack.Pose pose;
		private final VertexConsumer quads;
		private final VertexConsumer lines;
		private final float lineScale;

		private VertexTarget(PoseStack.Pose pose, VertexConsumer quads, VertexConsumer lines, float lineScale) {
			this.pose = pose;
			this.quads = quads;
			this.lines = lines;
			this.lineScale = Math.max(0.1f, lineScale);
		}

		@Override
		public void quad(double x0, double y0, double z0, double x1, double y1, double z1,
				double x2, double y2, double z2, double x3, double y3, double z3, int argb) {
			vertex(quads, x0, y0, z0, argb);
			vertex(quads, x1, y1, z1, argb);
			vertex(quads, x2, y2, z2, argb);
			vertex(quads, x3, y3, z3, argb);
		}

		@Override
		public void line(double x0, double y0, double z0, double x1, double y1, double z1,
				float width, int argb) {
			float nx = (float) (x1 - x0);
			float ny = (float) (y1 - y0);
			float nz = (float) (z1 - z0);
			float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (length < 1.0e-5f) {
				return;
			}
			nx /= length;
			ny /= length;
			nz /= length;
			lines.addVertex(pose, (float) x0, (float) y0, (float) z0)
					.setColor(argb).setNormal(pose, nx, ny, nz).setLineWidth(width * lineScale);
			lines.addVertex(pose, (float) x1, (float) y1, (float) z1)
					.setColor(argb).setNormal(pose, nx, ny, nz).setLineWidth(width * lineScale);
		}

		private void vertex(VertexConsumer vc, double x, double y, double z, int argb) {
			vc.addVertex(pose, (float) x, (float) y, (float) z).setColor(argb);
		}
	}
}
