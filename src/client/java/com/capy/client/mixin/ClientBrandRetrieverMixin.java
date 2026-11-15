package com.capy.client.mixin;

import com.capy.client.Stealth;
import net.minecraft.client.ClientBrandRetriever;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The brand string is built here and sent once during configuration. */
@Mixin(ClientBrandRetriever.class)
public class ClientBrandRetrieverMixin {
	@Inject(method = "getClientModName", at = @At("HEAD"), cancellable = true)
	private static void capy$stealthBrand(CallbackInfoReturnable<String> cir) {
		if (Stealth.enabled()) {
			cir.setReturnValue(ClientBrandRetriever.VANILLA_NAME);
		}
	}
}
