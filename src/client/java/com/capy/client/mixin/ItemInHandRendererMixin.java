package com.capy.client.mixin;

import com.capy.client.module.modules.knife.KnifePose;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.LocalPlayer;
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
 * Feeds the knife pose system from the first person hand renderer.
 *
 * Two hooks, and they are deliberately separate:
 *
 *  - {@code renderHandsWithItems} runs once per frame with the local player and
 *    the same partial tick vanilla uses for the arm swing. Sampling the attack
 *    animation there is what keeps our swing on exactly the same clock as the
 *    arm, and it is the only sample that is interpolated correctly.
 *
 *  - {@code renderItem} is the point where the pose stack is already in hand
 *    space (the arm transform has been applied) and the item has not been
 *    submitted yet, so a transform pushed here moves and turns the knife about
 *    the fist - exactly the space the stance, the swing and the flourish need.
 *    The display transform that makes a knife sit in the hand is applied
 *    further down, inside the render state, so it composes with this one and is
 *    not disturbed.
 *
 * The pose is pushed at HEAD and popped at RETURN, which is safe because
 * {@code renderItem} has only two exits (an empty stack, and the normal end) and
 * RETURN covers both.
 */
@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {
	@Inject(method = "renderHandsWithItems", at = @At("HEAD"))
	private void capy$knifeFrame(float partialTick, PoseStack pose, SubmitNodeCollector collector,
			LocalPlayer player, int light, CallbackInfo ci) {
		KnifePose.frame(player, partialTick);
	}

	@Inject(method = "renderItem", at = @At("HEAD"))
	private void capy$knifeBegin(LivingEntity entity, ItemStack stack,
			ItemDisplayContext context, PoseStack pose, SubmitNodeCollector collector,
			int light, CallbackInfo ci) {
		KnifePose.begin(pose, entity, stack, context);
	}

	@Inject(method = "renderItem", at = @At("RETURN"))
	private void capy$knifeEnd(LivingEntity entity, ItemStack stack,
			ItemDisplayContext context, PoseStack pose, SubmitNodeCollector collector,
			int light, CallbackInfo ci) {
		KnifePose.end(pose);
	}
}
