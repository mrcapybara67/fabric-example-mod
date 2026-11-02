package com.capy.client.module.modules.esp;

import com.capy.client.module.Module;
import com.capy.client.module.ModuleManager;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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
 * Three layers per box, all from vanilla render types:
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
 *       pass;</li>
 *   <li>optional tracers: a line from the centre of the crosshair to the
 *       centre of every box, in the same hue and under the same depth rule.
 *       Every line leaves from the same pixel, so a bundle of them reads as a
 *       fan of directions rather than as lines that start at six different
 *       places; they are the fastest way to answer "which direction is that"
 *       while keeping the boxes as the precise overlay.</li>
 *   </ul>
 *
 * Boxes are sorted far to near before anything is submitted. The fills are
 * translucent and do not write depth, so submitting them in world order would
 * let a near box be blended under a far one and produce a patchwork; sorting is
 * the only thing that makes overlapping highlights look correct.
 *
 * The event this is registered on runs <em>inside</em> the world frame pass.
 * That matters: in 1.21.11 the debug-render event is fired while the frame is
 * still being extracted, so an immediate draw submitted there has no pass to
 * land in and is silently discarded. The hook therefore lives on
 * {@code AFTER_ENTITIES}, where vanilla is already drawing entities with the
 * same buffer source mechanism.
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
	/** The builder behind {@link #batch}, kept so a dropped one can be freed. */
	private static ByteBufferBuilder buffer;

	private static final List<EspModule> ACTIVE = new ArrayList<>();
	/**
	 * The sorted boxes of every active module for this frame.
	 *
	 * Both passes need the same far-to-near order, and a module's boxes cost a
	 * walk (players) or a copy (everything else) to produce, so the list is
	 * built once per frame here and then read twice. A map keyed by identity
	 * keeps one list per module without allocating a pair for every box.
	 */
	private static final Map<EspModule, List<AABB>> SORTED = new IdentityHashMap<>();
	private static double camX;
	private static double camY;
	private static double camZ;
	/** Camera-relative origin shared by every tracer this frame. */
	private static double anchorX;
	private static double anchorY;
	private static double anchorZ;
	private static final Comparator<AABB> FAR_TO_NEAR = (a, b) -> Double.compare(distanceSqr(b), distanceSqr(a));

	private EspRenderer() {
	}

	private static double distanceSqr(AABB box) {
		double x = (box.minX + box.maxX) * 0.5 - camX;
		double y = (box.minY + box.maxY) * 0.5 - camY;
		double z = (box.minZ + box.maxZ) * 0.5 - camZ;
		return x * x + y * y + z * z;
	}

	/**
	 * World render hook: called once per frame from {@code AFTER_ENTITIES},
	 * while the main world pass is active.
	 */
	public static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return;
		}
		ACTIVE.clear();
		boolean anyFill = false;
		boolean anyLine = false;
		for (Module module : ModuleManager.get().modules()) {
			if (module instanceof EspModule esp && esp.isEnabled()) {
				ACTIVE.add(esp);
				anyFill |= esp.fills();
				anyLine |= esp.outlines() || esp.tracers();
			}
		}
		if (ACTIVE.isEmpty()) {
			return;
		}
		// A pipeline that was abandoned mid-session cannot be drawn through any
		// more. Whatever is still queued belongs to render types that no longer
		// exist, so it is flushed and freed here rather than reused: a stale
		// builder is what turns one failed draw into a crash on the next frame.
		if (EspRenderTypes.unavailable() && batch != null) {
			discard();
		}

		try {
			Vec3 camera = mc.gameRenderer.getMainCamera().position();
			camX = camera.x;
			camY = camera.y;
			camZ = camera.z;

			// The anchor is the crosshair. A point anywhere else on the view
			// axis projects to the centre of the screen, so a half block in
			// front of the eye is the smallest offset that keeps the line out of
			// the near plane while still starting exactly on the crosshair. The
			// up vector used to be subtracted from it, which pushed every tracer
			// below the crosshair and made the bundle aim at a point the player
			// was not looking at.
			var forward = mc.gameRenderer.getMainCamera().forwardVector();
			anchorX = forward.x() * 0.5;
			anchorY = forward.y() * 0.5;
			anchorZ = forward.z() * 0.5;

			// AFTER_ENTITIES is called after the frame pass has created its
			// matrix stack, so this is normally non-null. A null would silently
			// remove every overlay, which is exactly the failure this hook was
			// moved to fix, so an identity stack is used instead. The vertices
			// are camera relative, which is what an identity pose expects.
			PoseStack stack = context.matrices();
			if (stack == null) {
				stack = new PoseStack();
			}
			PoseStack.Pose pose = stack.last();
			MultiBufferSource.BufferSource batches = batch();
			// Line width is measured in framebuffer pixels by the shader, so it
			// has to follow the GUI scale to look the same on every screen.
			float crisp = Math.max(1.4f, mc.getWindow().getGuiScale() * 1.25f);
			float glow = crisp * GLOW_SCALE;

			// Every module's boxes are gathered and sorted once here, before
			// either pass: both the fills and the lines draw the same list in
			// the same order, and gathering twice per module per frame would
			// walk the player list twice and re-sort twice for no difference on
			// screen.
			prepare();

			// One layer at a time. A buffer source hands out a shared buffer for
			// custom render types and flushes the previous one as soon as another
			// type is requested, so two of them can never be held at once. Doing
			// the fills first also means the outlines are always drawn over them.
			drawLayers(batches, pose, crisp, glow, anyFill, anyLine);
		} catch (RuntimeException e) {
			// The see-through pipelines are copies of vanilla's, built at
			// runtime. If one of them cannot be compiled on this machine, the
			// overlay must still be drawn - with occlusion - instead of taking
			// the frame down with it. The catch also covers a malformed module
			// list: an ESP error must never stop the world from rendering.
			//
			// A throw can leave the shared builder half filled. Flush it (or
			// drop it if even the flush fails) so the next frame starts from a
			// clean source instead of inheriting a broken buffer.
			discard();
			if (!throughFailed) {
				throughFailed = true;
				EspRenderTypes.disable();
				LOGGER.warn("Capy: ESP draw failed ({}); falling back to the depth tested render types.",
						e.toString());
			}
		}
	}

	/**
	 * Builds this frame's sorted box list for every active module.
	 *
	 * Called once per frame, before either pass, so a module that draws both a
	 * fill and an outline is walked and sorted once.
	 */
	private static void prepare() {
		for (EspModule module : ACTIVE) {
			List<AABB> boxes = SORTED.get(module);
			if (boxes == null) {
				boxes = new ArrayList<>();
				SORTED.put(module, boxes);
			} else {
				boxes.clear();
			}
			boxes.addAll(module.boxes());
			if (boxes.size() > 1) {
				boxes.sort(FAR_TO_NEAR);
			}
		}
		// A module that was switched off since the last frame keeps no list:
		// the map is the cache, and a cache that never forgets would grow every
		// time a module is toggled.
		SORTED.keySet().retainAll(ACTIVE);
	}

	private static void drawLayers(MultiBufferSource.BufferSource batches, PoseStack.Pose pose,
			float crisp, float glow, boolean anyFill, boolean anyLine) {
		if (anyFill) {
			for (EspModule module : ACTIVE) {
				if (!module.fills()) {
					continue;
				}
				List<AABB> boxes = SORTED.get(module);
				if (boxes == null || boxes.isEmpty()) {
					continue;
				}
				VertexConsumer fills = batches.getBuffer(EspRenderTypes.quads());
				for (AABB box : boxes) {
					// The colour belongs to the box, not to the module: storage
					// draws a chest and a shulker box in different hues.
					int argb = module.colorFor(box);
					fill(fills, pose, box, EspModule.fillTop(argb), EspModule.fillBottom(argb));
				}
				// Flush per module: a module can contribute hundreds of boxes and
				// the next module must start from a fresh builder, because a
				// builder that has been drawn is no longer writable.
				batches.endBatch();
			}
		}
		if (anyLine) {
			for (EspModule module : ACTIVE) {
				if (!module.outlines() && !module.tracers()) {
					continue;
				}
				List<AABB> boxes = SORTED.get(module);
				if (boxes == null || boxes.isEmpty()) {
					continue;
				}
				VertexConsumer lines = batches.getBuffer(EspRenderTypes.lines());
				if (module.tracers()) {
					// Tracers go under the outlines: the box edge is the precise
					// information, the line is the pointer to it.
					for (AABB box : boxes) {
						int glowColor = halo(module.colorFor(box));
						tracer(lines, pose, box, glowColor, glow);
						tracer(lines, pose, box, module.colorFor(box), crisp);
					}
				}
				if (module.outlines()) {
					for (AABB box : boxes) {
						int glowColor = halo(module.colorFor(box));
						outline(lines, pose, box, glowColor, glow);
						outline(lines, pose, box, module.colorFor(box), crisp);
					}
				}
				batches.endBatch();
			}
		}
	}

	/**
	 * Flushes the current source as far as it will go, then frees it.
	 *
	 * <p>Both halves matter. A half written buffer must not be drawn into
	 * again, and the builder behind it holds native memory that is only
	 * returned when it is closed - so a failure that repeats every frame would
	 * leak megabytes a second if it were merely dereferenced.</p>
	 */
	private static void discard() {
		if (batch != null) {
			try {
				batch.endBatch();
			} catch (RuntimeException | LinkageError ignored) {
				// The source is already unusable; it is dropped either way.
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

	/** The reusable batch, created on the first frame that needs it. */
	private static MultiBufferSource.BufferSource batch() {
		if (batch == null) {
			buffer = new ByteBufferBuilder(BUFFER_BYTES);
			batch = MultiBufferSource.immediate(buffer);
		}
		return batch;
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

	/**
	 * One tracer: the shared camera anchor to the centre of a box. The anchor
	 * is already camera relative; only the box needs its offset removed.
	 */
	private static void tracer(VertexConsumer vc, PoseStack.Pose pose, AABB box, int argb, float width) {
		// The centre is computed inline: getCenter() allocates a Vec3, and a
		// block overlay can ask for a tracer on every one of its boxes.
		line(vc, pose, anchorX, anchorY, anchorZ,
				(box.minX + box.maxX) * 0.5 - camX,
				(box.minY + box.maxY) * 0.5 - camY,
				(box.minZ + box.maxZ) * 0.5 - camZ, argb, width);
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
