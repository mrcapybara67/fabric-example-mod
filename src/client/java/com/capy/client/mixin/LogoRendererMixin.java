package com.capy.client.mixin;

import com.capy.client.util.Guard;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.LogoRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Removes the vanilla wordmark from the title screen.
 *
 * <p>The custom scene owns the top of the screen: its crest, wordmark and
 * subtitle sit where the Minecraft logo used to be, and the vanilla logo is
 * wider than the whole title area on a phone. Cancelling the renderer is
 * cleaner than moving the scene around it, because the logo also draws the
 * edition badge, the splash text and its own drop shadow. None of those survive
 * here.</p>
 */
@Mixin(LogoRenderer.class)
public class LogoRendererMixin {
	@Inject(method = "renderLogo(Lnet/minecraft/client/gui/GuiGraphics;IF)V",
			at = @At("HEAD"), cancellable = true, require = 0)
	private void capy$hideLogo(GuiGraphics graphics, int height, float alpha,
			CallbackInfo ci) {
		try {
			ci.cancel();
		} catch (Throwable failure) {
			Guard.report("menu logo", failure);
		}
	}

	@Inject(method = "renderLogo(Lnet/minecraft/client/gui/GuiGraphics;IFI)V",
			at = @At("HEAD"), cancellable = true, require = 0)
	private void capy$hideLogoOffset(GuiGraphics graphics, int height, float alpha, int offset,
			CallbackInfo ci) {
		try {
			ci.cancel();
		} catch (Throwable failure) {
			Guard.report("menu logo", failure);
		}
	}
}
