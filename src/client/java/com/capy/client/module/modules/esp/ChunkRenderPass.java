package com.capy.client.module.modules.esp;

import com.capy.client.finder.core.ChunkStyles;
import com.capy.client.module.Module;
import com.capy.client.module.ModuleManager;
import com.capy.client.util.Guard;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the chunk-style layer for every enabled finder in the same world pass
 * and at the same point as {@link EspRenderer}.
 *
 * <p>The styles need both quads and lines in the same pass, so each kind has
 * its own immediate source. Switching render types inside one source would
 * flush the previous geometry, which is fine for vanilla but would make an
 * interleaved contour impossible to keep in order.</p>
 */
public final class ChunkRenderPass {
	private static final int QUAD_BUFFER_BYTES = 8 * 1024 * 1024;
	private static final int LINE_BUFFER_BYTES = 2 * 1024 * 1024;
	private static ByteBufferBuilder quadBuffer;
	private static ByteBufferBuilder lineBuffer;
	private static MultiBufferSource.BufferSource quadBatch;
	private static MultiBufferSource.BufferSource lineBatch;

	private ChunkRenderPass() {
	}

	public static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.level == null || mc.player == null) {
			return;
		}
		List<FinderModule> finders = finders();
		if (finders.isEmpty()) {
			return;
		}
		try {
			ensureBatches();
			Vec3 camera = mc.gameRenderer.getMainCamera().position();
			Frustum frustum = mc.levelRenderer.getCapturedFrustum();
			PoseStack stack = context.matrices();
			if (stack == null) {
				stack = new PoseStack();
			}
			VertexConsumer quads = quadBatch.getBuffer(EspRenderTypes.quads());
			VertexConsumer lines = lineBatch.getBuffer(EspRenderTypes.lines());
			SinkAdapter sink = new SinkAdapter(stack.last(), quads, lines, camera, frustum,
					mc.getWindow().getGuiScale());
			double seconds = System.nanoTime() / 1.0e9;
			double millis = System.currentTimeMillis();
			for (FinderModule finder : finders) {
				Guard.run("finder styles " + finder.id(), () -> finder.drawFlags(sink, seconds, millis));
			}
			// Agreement is drawn as a second, stronger plate over the whole
			// merged area. The individual styles still say what each finder saw;
			// this layer says that two kinds of evidence point at the same spot.
			for (FinderSignals.Group group : FinderSignals.collect(mc)) {
				if (!group.combined() || group.chunks().isEmpty()) {
					continue;
				}
				ChunkStyles.fillContour(sink, new LinkedHashSet<>(group.chunks()),
						surfaceY(mc, group.chunkX(), group.chunkZ()) + 0.06,
						group.color() & 0xFFFFFF, 78, 255, seconds);
			}
			if (sink.quadVertices() > 0) {
				quadBatch.endBatch();
			}
			if (sink.lineVertices() > 0) {
				lineBatch.endBatch();
			}
		} catch (RuntimeException | LinkageError failure) {
			Guard.report("finder styles", failure);
			discard();
		}
	}

	private static int surfaceY(Minecraft mc, int cx, int cz) {
		int surface = mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, cx * 16 + 8, cz * 16 + 8);
		return Math.max(mc.level.getMinY(), Math.min(mc.level.getMaxY() - 1, surface));
	}

	private static List<FinderModule> finders() {
		List<FinderModule> out = new ArrayList<>();
		for (Module module : ModuleManager.get().modules()) {
			if (module instanceof FinderModule finder && module.isEnabled()) {
				out.add(finder);
			}
		}
		return out;
	}

	private static void ensureBatches() {
		if (quadBatch == null) {
			quadBuffer = new ByteBufferBuilder(QUAD_BUFFER_BYTES);
			quadBatch = MultiBufferSource.immediate(quadBuffer);
		}
		if (lineBatch == null) {
			lineBuffer = new ByteBufferBuilder(LINE_BUFFER_BYTES);
			lineBatch = MultiBufferSource.immediate(lineBuffer);
		}
	}

	private static void discard() {
		if (quadBatch != null) {
			try {
				quadBatch.endBatch();
			} catch (RuntimeException | LinkageError failure) {
				Guard.report("finder styles quad flush", failure);
			}
			quadBatch = null;
		}
		if (lineBatch != null) {
			try {
				lineBatch.endBatch();
			} catch (RuntimeException | LinkageError failure) {
				Guard.report("finder styles line flush", failure);
			}
			lineBatch = null;
		}
		if (quadBuffer != null) {
			try {
				quadBuffer.close();
			} catch (RuntimeException | LinkageError failure) {
				Guard.report("finder styles quad release", failure);
			}
			quadBuffer = null;
		}
		if (lineBuffer != null) {
			try {
				lineBuffer.close();
			} catch (RuntimeException | LinkageError failure) {
				Guard.report("finder styles line release", failure);
			}
			lineBuffer = null;
		}
	}
}
