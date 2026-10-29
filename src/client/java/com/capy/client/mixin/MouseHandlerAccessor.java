package com.capy.client.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Access to the mouse deltas that have not been turned into camera rotation
 * yet.
 *
 * {@code handleAccumulatedMovement} samples these two fields, hands them to
 * {@code turnPlayer} and then zeroes them, all inside one call. Reading and
 * writing them at the head of that call is therefore the one place where a
 * module can decide how much of the player's movement is applied this frame and
 * how much is held back — which is exactly what a smoothing camera does. The
 * accessors change nothing else: sensitivity, the cinematic camera and the
 * third person behaviour all stay vanilla.
 */
@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {
	@Accessor("accumulatedDX")
	double capy$accumulatedDX();

	@Accessor("accumulatedDX")
	void capy$setAccumulatedDX(double value);

	@Accessor("accumulatedDY")
	double capy$accumulatedDY();

	@Accessor("accumulatedDY")
	void capy$setAccumulatedDY(double value);
}
