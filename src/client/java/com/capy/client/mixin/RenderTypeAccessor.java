package com.capy.client.mixin;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Access to {@code RenderType.create}, which vanilla keeps package private.
 *
 * The ESP overlays need a render type built from a pipeline whose depth test
 * is disabled, and there is no public factory for that. The accessor only
 * calls the method vanilla itself calls; it changes no behaviour.
 */
@Mixin(RenderType.class)
public interface RenderTypeAccessor {
	@Invoker("create")
	static RenderType capy$create(String name, RenderSetup setup) {
		throw new AssertionError("Mixin not applied");
	}
}
