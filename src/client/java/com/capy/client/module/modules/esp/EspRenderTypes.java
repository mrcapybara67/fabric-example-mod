package com.capy.client.module.modules.esp;

import com.capy.client.mixin.RenderTypeAccessor;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Render types for the ESP overlays, including the see-through pair.
 *
 * Vanilla's {@code lines} and {@code debugQuads} pipelines are depth tested, so
 * a container behind a wall simply disappears — which defeats the point of a
 * storage overlay. The two types here are copies of those pipelines with the
 * depth test turned off. Everything else (shaders, blending, vertex format,
 * depth-write behaviour) is inherited, so a see-through outline still draws
 * with the same line shader and the same soft fill as the normal one: only the
 * "is something in front of it" question is answered differently.
 *
 * The copies get their own pipeline location, so they are compiled and cached
 * as separate pipelines instead of aliasing the vanilla ones.
 *
 * Both are created on first use, on the render thread, and then reused for the
 * rest of the session.
 */
public final class EspRenderTypes {
	private static final Logger LOGGER = LoggerFactory.getLogger(EspRenderTypes.class);
	private static RenderType throughLines;
	private static RenderType throughQuads;
	/** True once creating the custom pipelines failed; the vanilla ones are used. */
	private static boolean unavailable;

	private EspRenderTypes() {
	}

	/**
	 * Gives up on the see-through types for the rest of the session.
	 *
	 * Called when a draw with them fails; a pipeline that cannot be compiled
	 * must cost the user the see-through effect, never the frame.
	 */
	public static void disable() {
		unavailable = true;
		throughLines = null;
		throughQuads = null;
	}

	/** Outline lines, depth tested or not. */
	public static RenderType lines(boolean throughWalls) {
		if (!throughWalls || unavailable) {
			return RenderTypes.lines();
		}
		if (throughLines == null) {
			throughLines = createSafely("esp_lines_through", RenderPipelines.LINES);
		}
		return throughLines == null ? RenderTypes.lines() : throughLines;
	}

	/** Filled box faces, depth tested or not. */
	public static RenderType quads(boolean throughWalls) {
		if (!throughWalls || unavailable) {
			return RenderTypes.debugQuads();
		}
		if (throughQuads == null) {
			throughQuads = createSafely("esp_quads_through", RenderPipelines.DEBUG_QUADS);
		}
		return throughQuads == null ? RenderTypes.debugQuads() : throughQuads;
	}

	/**
	 * Builds a custom type, but never at the cost of a crash: if the pipeline
	 * cannot be created (a mapping change after an update, for example) the
	 * depth-tested vanilla type is used for the rest of the session and the
	 * reason is logged once.
	 */
	private static RenderType createSafely(String name, RenderPipeline base) {
		try {
			return create(name, base);
		} catch (Throwable t) {
			unavailable = true;
			LOGGER.warn("Capy: could not build the see-through ESP render type ({}); using the depth-tested one.",
					t.toString());
			return null;
		}
	}

	private static RenderType create(String name, RenderPipeline base) {
		RenderPipeline pipeline = new NoDepthPipeline(base,
				Identifier.fromNamespaceAndPath("capy", "pipeline/" + name));
		RenderSetup setup = RenderSetup.builder(pipeline)
				.bufferSize(RenderType.SMALL_BUFFER_SIZE)
				.createRenderSetup();
		return RenderTypeAccessor.capy$create("capy_" + name, setup);
	}

	/**
	 * A copy of a vanilla pipeline with the depth test disabled.
	 *
	 * {@link RenderPipeline} is immutable, so the copy is made by handing every
	 * setting back to the constructor it was built with. Shader programs are
	 * keyed by the shader locations and defines, not by the pipeline object, so
	 * the copy still uses vanilla's compiled line and quad shaders.
	 */
	private static final class NoDepthPipeline extends RenderPipeline {
		NoDepthPipeline(RenderPipeline base, Identifier location) {
			super(location,
					base.getVertexShader(),
					base.getFragmentShader(),
					base.getShaderDefines(),
					base.getSamplers(),
					base.getUniforms(),
					base.getBlendFunction(),
					DepthTestFunction.NO_DEPTH_TEST,
					base.getPolygonMode(),
					base.isCull(),
					base.isWriteColor(),
					base.isWriteAlpha(),
					// Nothing is in front of a see-through box, so it must not fill
					// the depth buffer either: the geometry of an overlay drawn
					// after it would otherwise be clipped by a wall that is not
					// actually drawn there.
					false,
					base.getColorLogic(),
					base.getVertexFormat(),
					base.getVertexFormatMode(),
					base.getDepthBiasScaleFactor(),
					base.getDepthBiasConstant(),
					base.getSortKey());
		}
	}
}
