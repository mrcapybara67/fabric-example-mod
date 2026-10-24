package com.capy.client.module.modules.esp;

import com.capy.client.module.Module;
import com.capy.client.module.ModuleManager;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Draws every enabled {@link EspModule} into the world.
 *
 * Two layers per box, both from vanilla render types:
 *
 *   <ul>
 *   <li>a translucent fill ({@code debugQuads}) whose top edge is a little
 *       stronger than its bottom, so a face reads as a surface with a light
 *       source rather than a flat tint. It writes no depth, which is what keeps
 *       a box from hiding the one behind it;</li>
 *   <li>a crisp outline ({@code lines}) with a wider, faint copy behind it. The
 *       second pass is the whole trick behind the "drawn with a pen" look: a
 *       single line at this distance is a hairline, while a faint bloom under
 *       the bright core reads as thickness without a second geometry
 *       pass.</li>
 *   </ul>
 *
 * Boxes are sorted far to near before anything is submitted. The fills are
 * translucent and do not write depth, so submitting them in world order would
 * let a near box be blended under a far one and produce a patchwork; sorting is
 * the only thing that makes overlapping highlights look correct.
 *
 * Vertex coordinates are camera relative, which is what a world render
 * consumer expects. The matrix stack handed to the event is already the
 * camera's, so the only conversion is subtracting the camera position.
 */
public final class EspRenderer {
	private static final Logger LOGGER = LoggerFactory.getLogger(EspRenderer.class);
	/** True once a see-through draw failed; the depth tested types are used after that. */
	private static boolean throughFailed;
	/** Fraction of the outline alpha used by the halo under each edge. */
	private static final float GLOW_ALPHA = 0.20f;
	/** How much wider the halo is than the core line. */
	private static final float GLOW_SCALE = 3.1f;

	/**
	 * Own buffer instead of the world renderer's shared one: the geometry of
	 * several hundred boxes does not fit in the 768 KiB shared buffer, and a
	 * private source lets each layer be flushed on the spot instead of hoping
	 * the world renderer flushes a type it has never heard of.
	 */
	private static final int BUFFER_BYTES = 2 * 1024 * 1024;
	private static MultiBufferSource.BufferSource batch;

	private static final List<EspModule> ACTIVE = new ArrayList<>();
	private static final List<AABB> BOXES = new ArrayList<>();
	private static double camX;
	private static double camY;
	private static double camZ;
	private static final Comparator<AABB> FAR_TO_NEAR = (a, b) -> Double.compare(distanceSqr(b), distanceSqr(a));

	private EspRenderer() {
	}

	private static double distanceSqr(AABB box) {
		double x = (box.minX + box.maxX) * 0.5 - camX;
		double y = (box.minY + box.maxY) * 0.5 - camY;
		double z = (box.minZ + box.maxZ) * 0.5 - camZ;
		return x * x + y * y + z * z;
	}

	/** World render hook: called once per frame, in the debug pass. */
	public static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return;
		}
		ACTIVE.clear();
		boolean anyFill = false;
		boolean anyOutline = false;
		for (Module module : ModuleManager.get().modules()) {
			if (module instanceof EspModule esp && esp.isEnabled()) {
				ACTIVE.add(esp);
				anyFill |= esp.fills();
				anyOutline |= esp.outlines();
			}
		}
		if (ACTIVE.isEmpty()) {
			return;
		}

		Vec3 camera = mc.gameRenderer.getMainCamera().position();
		camX = camera.x;
		camY = camera.y;
		camZ = camera.z;

		PoseStack stack = context.matrices();
		if (stack == null) {
			return;
		}
		PoseStack.Pose pose = stack.last();
		MultiBufferSource.BufferSource batches = batch();
		// Line width is measured in framebuffer pixels by the shader, so it has
		// to follow the GUI scale to look the same on every screen.
		float crisp = Math.max(1.4f, mc.getWindow().getGuiScale() * 1.25f);
		float glow = crisp * GLOW_SCALE;

		// One layer at a time. A buffer source hands out a shared buffer for
		// custom render types and flushes the previous one as soon as another
		// type is requested, so two of them can never be held at once. Doing
		// the fills first also means the outlines are always drawn over them.
		try {
			drawLayers(batches, pose, crisp, glow, anyFill, anyOutline);
		} catch (RuntimeException e) {
			// The see-through pipelines are copies of vanilla's, built at
			// runtime. If one of them cannot be compiled on this machine, the
			// overlay must still be drawn - with occlusion - instead of taking
			// the frame down with it.
			if (!throughFailed) {
				throughFailed = true;
				EspRenderTypes.disable();
				LOGGER.warn("Capy: see-through ESP failed ({}); using the depth tested render types.",
						e.toString());
			}
		}
	}

	private static void drawLayers(MultiBufferSource.BufferSource batches, PoseStack.Pose pose,
			float crisp, float glow, boolean anyFill, boolean anyOutline) {
		if (anyFill) {
			for (EspModule module : ACTIVE) {
				if (!module.fills()) {
					continue;
				}
				gather(module);
				if (BOXES.isEmpty()) {
					continue;
				}
				VertexConsumer fills = batches.getBuffer(EspRenderTypes.quads(module.throughWalls()));
				int top = module.fillTop();
				int bottom = module.fillBottom();
				for (AABB box : BOXES) {
					fill(fills, pose, box, top, bottom);
				}
				// Flush per module: a module can contribute hundreds of boxes and
				// the next module must start from a fresh builder, because a
				// builder that has been drawn is no longer writable.
				batches.endBatch();
			}
		}
		if (anyOutline) {
			for (EspModule module : ACTIVE) {
				if (!module.outlines()) {
					continue;
				}
				gather(module);
				if (BOXES.isEmpty()) {
					continue;
				}
				VertexConsumer lines = batches.getBuffer(EspRenderTypes.lines(module.throughWalls()));
				int color = module.color();
				int glowColor = halo(color);
				for (AABB box : BOXES) {
					outline(lines, pose, box, glowColor, glow);
					outline(lines, pose, box, color, crisp);
				}
				batches.endBatch();
			}
		}
	}

	/** The reusable batch, created on the first frame that needs it. */
	private static MultiBufferSource.BufferSource batch() {
		if (batch == null) {
			batch = MultiBufferSource.immediate(new ByteBufferBuilder(BUFFER_BYTES));
		}
		return batch;
	}

	/** Copies a module's boxes into the scratch list, far to near. */
	private static void gather(EspModule module) {
		BOXES.clear();
		BOXES.addAll(module.boxes());
		if (BOXES.size() > 1) {
			BOXES.sort(FAR_TO_NEAR);
		}
	}

	/** The same hue at a fixed fraction of the outline alpha. */
	private static int halo(int argb) {
		int alpha = Math.round(((argb >>> 24) & 0xFF) * GLOW_ALPHA);
		return (Math.max(0, Math.min(255, alpha)) << 24) | (argb & 0xFFFFFF);
	}

	// ------------------------------------------------------------- geometry

	/**
	 * Six faces of a box. The fill pipeline culls nothing, so a face is visible
	 * from both sides and the winding below is only a convention. Sides blend
	 * from the brighter top colour to the darker bottom one.
	 */
	private static void fill(VertexConsumer vc, PoseStack.Pose pose, AABB box, int top, int bottom) {
		double x1 = box.minX - camX;
		double y1 = box.minY - camY;
		double z1 = box.minZ - camZ;
		double x2 = box.maxX - camX;
		double y2 = box.maxY - camY;
		double z2 = box.maxZ - camZ;
		// North (-Z)
		vertex(vc, pose, x1, y1, z1, bottom);
		vertex(vc, pose, x1, y2, z1, top);
		vertex(vc, pose, x2, y2, z1, top);
		vertex(vc, pose, x2, y1, z1, bottom);
		// South (+Z)
		vertex(vc, pose, x2, y1, z2, bottom);
		vertex(vc, pose, x2, y2, z2, top);
		vertex(vc, pose, x1, y2, z2, top);
		vertex(vc, pose, x1, y1, z2, bottom);
		// West (-X)
		vertex(vc, pose, x1, y1, z1, bottom);
		vertex(vc, pose, x1, y1, z2, bottom);
		vertex(vc, pose, x1, y2, z2, top);
		vertex(vc, pose, x1, y2, z1, top);
		// East (+X)
		vertex(vc, pose, x2, y1, z2, bottom);
		vertex(vc, pose, x2, y1, z1, bottom);
		vertex(vc, pose, x2, y2, z1, top);
		vertex(vc, pose, x2, y2, z2, top);
		// Top (+Y)
		vertex(vc, pose, x1, y2, z1, top);
		vertex(vc, pose, x1, y2, z2, top);
		vertex(vc, pose, x2, y2, z2, top);
		vertex(vc, pose, x2, y2, z1, top);
		// Bottom (-Y)
		vertex(vc, pose, x1, y1, z2, bottom);
		vertex(vc, pose, x1, y1, z1, bottom);
		vertex(vc, pose, x2, y1, z1, bottom);
		vertex(vc, pose, x2, y1, z2, bottom);
	}

	/** The twelve edges of a box, each one line segment wide. */
	private static void outline(VertexConsumer vc, PoseStack.Pose pose, AABB box, int argb, float width) {
		double x1 = box.minX - camX;
		double y1 = box.minY - camY;
		double z1 = box.minZ - camZ;
		double x2 = box.maxX - camX;
		double y2 = box.maxY - camY;
		double z2 = box.maxZ - camZ;
		// Bottom ring.
		line(vc, pose, x1, y1, z1, x2, y1, z1, argb, width);
		line(vc, pose, x2, y1, z1, x2, y1, z2, argb, width);
		line(vc, pose, x2, y1, z2, x1, y1, z2, argb, width);
		line(vc, pose, x1, y1, z2, x1, y1, z1, argb, width);
		// Top ring.
		line(vc, pose, x1, y2, z1, x2, y2, z1, argb, width);
		line(vc, pose, x2, y2, z1, x2, y2, z2, argb, width);
		line(vc, pose, x2, y2, z2, x1, y2, z2, argb, width);
		line(vc, pose, x1, y2, z2, x1, y2, z1, argb, width);
		// Uprights.
		line(vc, pose, x1, y1, z1, x1, y2, z1, argb, width);
		line(vc, pose, x2, y1, z1, x2, y2, z1, argb, width);
		line(vc, pose, x2, y1, z2, x2, y2, z2, argb, width);
		line(vc, pose, x1, y1, z2, x1, y2, z2, argb, width);
	}

	private static void vertex(VertexConsumer vc, PoseStack.Pose pose,
			double x, double y, double z, int argb) {
		vc.addVertex(pose, (float) x, (float) y, (float) z).setColor(argb);
	}

	/**
	 * One line segment. The normal carries the segment direction in world
	 * space; the line shader uses it, together with the second vertex, to
	 * expand the segment into a screen-space quad of the requested width.
	 */
	private static void line(VertexConsumer vc, PoseStack.Pose pose,
			double x1, double y1, double z1, double x2, double y2, double z2,
			int argb, float width) {
		float nx = (float) (x2 - x1);
		float ny = (float) (y2 - y1);
		float nz = (float) (z2 - z1);
		float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (length < 1.0e-5f) {
			return;
		}
		nx /= length;
		ny /= length;
		nz /= length;
		vc.addVertex(pose, (float) x1, (float) y1, (float) z1)
				.setColor(argb).setNormal(pose, nx, ny, nz).setLineWidth(width);
		vc.addVertex(pose, (float) x2, (float) y2, (float) z2)
				.setColor(argb).setNormal(pose, nx, ny, nz).setLineWidth(width);
	}
}
