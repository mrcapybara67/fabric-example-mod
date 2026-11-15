package com.capy.client.mixin;

import com.capy.client.stealth.CapyText;
import net.minecraft.client.gui.screens.options.controls.KeyBindsList;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Resolves the name column for Capy bindings through Capy's own text table. */
@Mixin(KeyBindsList.class)
public class KeyBindsListMixin {
	@Redirect(method = "refreshEntries", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/network/chat/Component;translatable(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;"))
	private MutableComponent capy$bindingName(String key) {
		if (!key.startsWith("key.capy.")) {
			return Component.translatable(key);
		}
		String text = CapyText.get(key, "");
		return Component.literal(text.isEmpty() ? key : text);
	}
}
