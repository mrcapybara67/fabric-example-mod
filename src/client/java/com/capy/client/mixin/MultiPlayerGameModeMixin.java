package com.capy.client.mixin;

import com.capy.client.antivanish.VanishPackets;
import com.capy.client.util.Guard;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Records the local player's outgoing action timestamps without sending anything. */
@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {
	@Inject(method = "useItemOn", at = @At("HEAD"))
	private void capy$onUseItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<?> ci) {
		Guard.run("anti-vanish use item on", () -> {
			VanishPackets.onAction();
			ItemStack stack = player.getItemInHand(hand);
			if (stack.getItem() instanceof BlockItem blockItem && hit != null) {
				BlockPos pos = hit.getBlockPos().relative(hit.getDirection());
				VanishPackets.onPlaceAttempt(pos, blockItem.getBlock());
			}
		});
	}

	@Inject(method = "useItem", at = @At("HEAD"))
	private void capy$onUseItem(Player player, InteractionHand hand, CallbackInfoReturnable<?> ci) {
		Guard.run("anti-vanish use item", VanishPackets::onAction);
	}

	@Inject(method = "startDestroyBlock", at = @At("HEAD"))
	private void capy$onStartDestroyBlock(BlockPos pos, net.minecraft.core.Direction direction, CallbackInfoReturnable<?> ci) {
		Guard.run("anti-vanish start dig", VanishPackets::onAction);
	}

	@Inject(method = "continueDestroyBlock", at = @At("HEAD"))
	private void capy$onContinueDestroyBlock(BlockPos pos, net.minecraft.core.Direction direction, CallbackInfoReturnable<?> ci) {
		Guard.run("anti-vanish continue dig", VanishPackets::onAction);
	}

	@Inject(method = "destroyBlock", at = @At("HEAD"))
	private void capy$onDestroyBlock(BlockPos pos, CallbackInfoReturnable<?> ci) {
		Guard.run("anti-vanish dig", VanishPackets::onAction);
	}

	@Inject(method = "attack", at = @At("HEAD"))
	private void capy$onAttack(Player player, Entity target, CallbackInfo ci) {
		Guard.run("anti-vanish attack", VanishPackets::onAction);
	}

	@Inject(method = "interact", at = @At("HEAD"))
	private void capy$onInteract(Player player, Entity target, InteractionHand hand, CallbackInfoReturnable<?> ci) {
		Guard.run("anti-vanish interact", VanishPackets::onAction);
	}

	@Inject(method = "interactAt", at = @At("HEAD"))
	private void capy$onInteractAt(Player player, Entity target, EntityHitResult hit, InteractionHand hand,
			CallbackInfoReturnable<?> ci) {
		Guard.run("anti-vanish interact at", VanishPackets::onAction);
	}
}
