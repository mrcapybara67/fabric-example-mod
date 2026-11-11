package com.capy.client.mixin;

import com.capy.client.module.modules.esp.ChunkFinderModule;
import com.capy.client.util.Guard;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Marks the local player's own break so light flags near it are suppressed. */
@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {
	@Inject(method = "destroyBlock", at = @At("HEAD"), require = 0)
	private void capy$localBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		Guard.run("light local break", () -> ChunkFinderModule.onLocalBreak(pos));
	}
}
