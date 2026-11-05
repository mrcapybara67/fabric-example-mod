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
 * Render types for the ESP overlays: the see-through pair, used for every box
 * by every overlay.
 *
 * Vanilla's {@code lines} and {@code debugQuads} pipelines are depth tested, so
 * a container behind a wall simply disappears — which defeats the point of a
 * storage overlay. The two types here are copies of those pipelines with the
 * depth test turned off. Everything else (blending, vertex format, depth-write
 * behaviour) is inherited, so a see-through outline still draws with the same
 * geometry as the normal one: only the "is something in front of it" question
 * is answered differently.
 *
 * <h2>Why the outline also has its own shader</h2>
 * Vanilla's line shader fogs its output unconditionally: it mixes the line's
 * colour towards the frame's fog colour by the vertex distance, so an overlay
 * drawn through water, rain or a long view distance changes hue with the world
 * around it. That is right for a hitbox and wrong for an overlay — a red box
 * must stay red — so the line pipeline points at a copy of the line shader with
 * the fog call removed. The fill needs no such copy: it draws through
 * {@code position_color}, which never applied fog in the first place.
 *
 * The copies get their own pipeline location, so they are compiled and cached
 * as separate pipelines instead of aliasing the vanilla ones.
 *
 * Both are created on first use, on the render thread, and then reused for the
 * rest of the session.
 */
public final class EspRenderTypes {
	private static final Logger LOGGER = LoggerFactory.getLogger(EspRenderTypes.class);
	/** The fogless line shader pair shipped with the mod. */
	private static final Identifier ESP_LINE_SHADER =
			Identifier.fromNamespaceAndPath("capy", "core/esp_lines");
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

	/**
	 * True once the see-through types were abandoned for this session.
	 *
	 * <p>The renderer keeps a buffer source of its own, and a source whose
	 * layers were built from a pipeline that no longer exists can never be
	 * flushed cleanly: the caller has to throw it away before drawing again.</p>
	 */
	public static boolean unavailable() {
		return unavailable;
	}

	/** Outline lines, always without a depth test. */
	public static RenderType lines() {
		if (unavailable) {
			return RenderTypes.lines();
		}
		if (throughLines == null) {
			throughLines = createSafely("esp_lines_through", RenderPipelines.LINES,
					ESP_LINE_SHADER, ESP_LINE_SHADER);
		}
		return throughLines == null ? RenderTypes.lines() : throughLines;
	}

	/** Filled box faces, always without a depth test. */
	public static RenderType quads() {
		if (unavailable) {
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
		return createSafely(name, base, base.getVertexShader(), base.getFragmentShader());
	}

	/**
	 * Builds a custom type with the given shader pair, but never at the cost of
	 * a crash.
	 *
	 * <p>A missing or uncompilable shader is a mapping/resource problem, not a
	 * reason to take the frame down: the depth-tested vanilla type is used for
	 * the rest of the session and the reason is logged once.</p>
	 */
	private static RenderType createSafely(String name, RenderPipeline base,
			Identifier vertexShader, Identifier fragmentShader) {
		try {
			return create(name, base, vertexShader, fragmentShader);
		} catch (Throwable t) {
			unavailable = true;
			LOGGER.warn("Capy: could not build the see-through ESP render type ({}); using the depth-tested one.",
					t.toString());
			return null;
		}
	}

	private static RenderType create(String name, RenderPipeline base) {
		return create(name, base, base.getVertexShader(), base.getFragmentShader());
	}

	private static RenderType create(String name, RenderPipeline base,
			Identifier vertexShader, Identifier fragmentShader) {
		RenderPipeline pipeline = new NoDepthPipeline(base,
				Identifier.fromNamespaceAndPath("capy", "pipeline/" + name),
				vertexShader, fragmentShader);
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
			this(base, location, base.getVertexShader(), base.getFragmentShader());
		}

		NoDepthPipeline(RenderPipeline base, Identifier location,
				Identifier vertexShader, Identifier fragmentShader) {
			super(location,
					vertexShader,
					fragmentShader,
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
