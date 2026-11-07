package com.capy.client.module.modules;

import com.capy.client.mixin.RenderTypeAccessor;
import com.capy.client.util.Guard;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/**
 * A render type for the atmosphere skybox.
 *
 * <p>The dome is drawn before terrain with the depth test and depth writes
 * disabled. Terrain therefore covers it completely, no matter which terrain
 * layer draws later, while the sky still replaces the vanilla panorama. A
 * depth-tested sky at this distance cannot be used: a dome large enough to sit
 * behind the horizon would also clip distant terrain that is farther away.</p>
 */
public final class AtmosphereRenderTypes {
	private static RenderType sky;
	private static boolean unavailable;

	private AtmosphereRenderTypes() {
	}

	/** The skybox type, or null when the custom pipeline could not be built. */
	public static RenderType sky() {
		if (unavailable) {
			return null;
		}
		if (sky == null) {
			try {
				RenderPipeline pipeline = new NoDepthPipeline(RenderPipelines.DEBUG_QUADS,
						Identifier.fromNamespaceAndPath("capy", "pipeline/atmosphere_sky"));
				RenderSetup setup = RenderSetup.builder(pipeline)
						.bufferSize(RenderType.SMALL_BUFFER_SIZE)
						.createRenderSetup();
				sky = RenderTypeAccessor.capy$create("capy_atmosphere_sky", setup);
			} catch (Throwable failure) {
				// No skybox is better than a depth-writing one: the vanilla sky
				// remains, and the rest of Atmosphere keeps working.
				unavailable = true;
				Guard.report("atmosphere sky render type", failure);
				return null;
			}
		}
		return sky;
	}

	/** A copy of the debug-quad pipeline with the depth stage removed. */
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
					// Seen from inside, a culled dome loses whichever half faces
					// away from the current vertex order. Culling is worth nothing
					// for a few hundred sky quads, so always draw both sides.
					false,
					base.isWriteColor(),
					base.isWriteAlpha(),
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
