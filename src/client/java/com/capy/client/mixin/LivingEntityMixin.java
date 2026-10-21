package com.capy.client.mixin;

import com.capy.client.MinecraftAccessorHolder;
import com.capy.client.module.ModuleManager;
import com.capy.client.module.modules.SwingSpeedModule;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Swing Speed, implemented as a scale on vanilla's own swing duration.
 *
 * How vanilla drives the first-person swing animation (verified against the
 * 1.21.11 bytecode):
 *
 *   LivingEntity.updateSwingTime()   // called every tick from baseTick()
 *       duration = getCurrentSwingDuration()
 *       if swinging:  swingTime++; if swingTime >= duration -> reset the swing
 *       else:         swingTime = 0
 *       attackAnim = swingTime / duration   // <- what the hand renderer reads
 *
 * {@code getCurrentSwingDuration()} is <em>per item</em>: it reads the held
 * stack's {@code swing_animation} component and then applies the dig speed and
 * mining fatigue effects. A sword, a pickaxe and a block all swing with
 * their own rhythm, and mining fatigue slows them down.
 *
 * The earlier implementation cancelled {@code updateSwingTime} and replayed it
 * with a fixed duration. That threw the per item rhythm away: every tool fell
 * back to the same generic swing, which is what made the sword look wrong.
 *
 * This mixin instead scales the duration vanilla computed, so:
 *   - every tool keeps its own animation shape;
 *   - mining fatigue and dig speed keep working, because vanilla still
 *     computes them and the module only multiplies the result;
 *   - the swing state machine, the interpolation between ticks and the
 *     block breaking animation stay completely vanilla, which is what makes
 *     the slowed swing smooth instead of stuttery.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@ModifyVariable(method = "updateSwingTime", at = @At("STORE"), ordinal = 0)
	private int capy$scaleSwingDuration(int duration) {
		SwingSpeedModule module = ModuleManager.get().get(SwingSpeedModule.class);
		if (module == null || !module.isEnabled()) {
			return duration;
		}
		// Only our own arm is affected; other players keep the vanilla rhythm.
		if (MinecraftAccessorHolder.player() != (Object) this) {
			return duration;
		}
		return module.scaleSwingDuration(duration);
	}
}
