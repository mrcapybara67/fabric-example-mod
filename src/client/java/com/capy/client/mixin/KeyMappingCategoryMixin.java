package com.capy.client.mixin;

import com.capy.client.stealth.CapyText;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The Controls screen gets Capy's category name from Capy's text loader. */
@Mixin(KeyMapping.Category.class)
public class KeyMappingCategoryMixin {
	@Inject(method = "label", at = @At("HEAD"), cancellable = true)
	private void capy$ownCategoryLabel(CallbackInfoReturnable<Component> cir) {
		String key = "key.category." + id().getNamespace() + "." + id().getPath();
		String text = CapyText.get(key, "");
		if (!text.isEmpty()) {
			cir.setReturnValue(Component.literal(text));
		}
	}

	private net.minecraft.resources.Identifier id() {
		return ((KeyMapping.Category) (Object) this).id();
	}
}
