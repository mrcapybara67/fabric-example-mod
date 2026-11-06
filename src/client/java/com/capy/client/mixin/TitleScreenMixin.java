package com.capy.client.mixin;

import com.capy.client.gui.MenuBackdrop;
import com.capy.client.util.Guard;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the title screen's panorama with the client's own animated scene.
 *
 * <p>{@code renderBackground} is the single call that paints the panorama, the
 * blur and the darkening before any widget exists. Cancelling it there and
 * drawing the backdrop instead leaves the buttons, the logo, the splash and
 * every vanilla interaction untouched - only the wallpaper changes.</p>
 */
@Mixin(TitleScreen.class)
public class TitleScreenMixin {
	@Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
	private void capy$menuBackdrop(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
			CallbackInfo ci) {
		try {
			MenuBackdrop.render(graphics);
			ci.cancel();
		} catch (Throwable failure) {
			// A broken backdrop must leave the vanilla one in place.
			Guard.report("title backdrop", failure);
		}
	}
}
