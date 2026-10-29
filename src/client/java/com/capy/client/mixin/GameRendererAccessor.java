package com.capy.client.mixin;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Access to the FOV modifier vanilla derives from potions and movement.
 *
 * {@code getFov} multiplies the FOV option by this value, so a custom FOV can
 * either ignore it (the "Override Effects" setting) or reproduce vanilla's own
 * speed and potion widening without copying the effect maths.
 */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
	@Accessor("fovModifier")
	float capy$fovModifier();
}
