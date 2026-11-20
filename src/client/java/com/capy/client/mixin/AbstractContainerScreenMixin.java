package com.capy.client.mixin;

import com.capy.client.module.ModuleManager;
import com.capy.client.module.modules.antivanish.AntiVanishModule;
import com.capy.client.util.Guard;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The HUD is rendered before screens, so a HUD alert would sit behind an open
 * inventory. The alert is still owned by the HUD layer for the normal case;
 * this tail hook adds only the over-screen copy while a container is open.
 */
@Mixin(AbstractContainerScreen.class)
public class AbstractContainerScreenMixin {
	@Inject(method = "render", at = @At("TAIL"), require = 0)
	private void capy$antiVanishOverInventory(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
			CallbackInfo ci) {
		Guard.run("anti-vanish inventory alert", () -> {
			AntiVanishModule module = ModuleManager.get().get(AntiVanishModule.class);
			if (module != null) {
				module.onInventoryHudRender(graphics, graphics.guiWidth(), graphics.guiHeight());
			}
		});
	}
}
