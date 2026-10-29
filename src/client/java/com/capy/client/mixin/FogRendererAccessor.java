package com.capy.client.mixin;

import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Access to vanilla's global fog switch.
 *
 * {@code fogEnabled} is the flag the F3 fog key toggles, and its only vanilla
 * entry point is {@code toggleFog()} — which flips it instead of setting it, so
 * a module could not tell which state it left behind. The accessors read and
 * write the flag itself, so a theme can switch the distance fog off and put
 * back exactly the state the player had before.
 */
@Mixin(FogRenderer.class)
public interface FogRendererAccessor {
	@Accessor("fogEnabled")
	static boolean capy$fogEnabled() {
		throw new AssertionError("Mixin not applied");
	}

	@Accessor("fogEnabled")
	static void capy$setFogEnabled(boolean value) {
		throw new AssertionError("Mixin not applied");
	}
}
