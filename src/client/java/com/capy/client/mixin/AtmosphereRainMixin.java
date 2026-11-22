package com.capy.client.mixin;

import com.capy.client.module.modules.AtmosphereRain;
import com.capy.client.module.modules.AtmosphereState;
import com.capy.client.util.Guard;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Gives vanilla rain the active theme's colour.
 *
 * <p>{@code WeatherEffectRenderer} builds each column's vertex colour with
 * {@code ARGB.white(alpha)} inside {@code renderInstances}. The integer local
 * at slot 14 is that colour; replacing it at the store recolours all four
 * vertices of the column without replacing the rain geometry, the texture or
 * the light lookup. The float argument is {@code 1.0} for rain and {@code 0.8}
 * for snow in 1.21.11, so the snow pass keeps its own white colour.</p>
 *
 * <p>The mixin is deliberately quiet when Atmosphere is off. It also catches
 * its own failures: this runs in the middle of a world pass, where an escaping
 * exception would crash the frame rather than simply leave the rain grey.</p>
 */
@Mixin(WeatherEffectRenderer.class)
public class AtmosphereRainMixin {
	@ModifyVariable(method = "renderInstances", at = @At("STORE"), index = 14)
	private int capy$themeRain(int color, VertexConsumer consumer,
			List<WeatherEffectRenderer.ColumnInstance> columns, Vec3 camera,
			float distanceFactor, int radius, float intensity) {
		try {
			if (AtmosphereState.active && AtmosphereRain.isRain(distanceFactor)) {
				return AtmosphereRain.tint(color, AtmosphereState.theme);
			}
		} catch (Throwable failure) {
			Guard.report("atmosphere rain tint", failure);
		}
		return color;
	}
}
