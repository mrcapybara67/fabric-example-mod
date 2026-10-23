package com.capy.client.mixin;

import com.capy.client.module.modules.knife.KnifeInspect;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wraps the item draw in the first person hand with the inspect flourish.
 *
 * {@code ItemInHandRenderer.renderItem} is the point where the pose stack is
 * already in hand space (the arm transform has been applied) and the item has
 * not been submitted yet, so a transform pushed here rotates the knife about
 * the hand - exactly the pivot a flourish needs. The display transform that
 * makes a knife sit in the hand is applied further down, inside the render
 * state, so it composes with this one and is not disturbed.
 *
 * The pose is pushed at HEAD and popped at RETURN, which is safe because
 * {@code renderItem} has only two exits (an empty stack, and the normal end)
 * and RETURN covers both.
 */
@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {
	@Inject(method = "renderItem", at = @At("HEAD"))
	private void capy$inspectBegin(LivingEntity entity, ItemStack stack,
			ItemDisplayContext context, PoseStack pose, SubmitNodeCollector collector,
			int light, CallbackInfo ci) {
		KnifeInspect.begin(pose, entity, stack, context);
	}

	@Inject(method = "renderItem", at = @At("RETURN"))
	private void capy$inspectEnd(LivingEntity entity, ItemStack stack,
			ItemDisplayContext context, PoseStack pose, SubmitNodeCollector collector,
			int light, CallbackInfo ci) {
		KnifeInspect.end(pose);
	}
}
