package com.capy.client.mixin;

import com.capy.client.stealth.CapyText;
import com.capy.client.util.Guard;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The Controls screen gets Capy's category name from Capy's text loader. */
@Mixin(KeyMapping.Category.class)
public class KeyMappingCategoryMixin {
	@Inject(method = "label", at = @At("HEAD"), cancellable = true, require = 0)
	private void capy$ownCategoryLabel(CallbackInfoReturnable<Component> cir) {
		try {
			Identifier id = capy$categoryId();
			// Vanilla categories keep their own translation. Only Capy's own
			// category is resolved through Capy's text table.
			if (!"capy".equals(id.getNamespace())) {
				return;
			}
			String key = "key.category." + id.getNamespace() + "." + id.getPath();
			String text = CapyText.get(key, "");
			cir.setReturnValue(Component.literal(text.isEmpty() ? id.toString() : text));
		} catch (RuntimeException | LinkageError failure) {
			// A label must never be able to take down the Controls screen.
			Guard.report("keybind category label", failure);
			cir.setReturnValue(Component.literal("capy:keybind"));
		}
	}

	@Unique
	private Identifier capy$categoryId() {
		return ((KeyMapping.Category) (Object) this).id();
	}
}
