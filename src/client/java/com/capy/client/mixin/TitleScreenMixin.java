package com.capy.client.mixin;

import com.capy.client.config.ClientConfig;
import com.capy.client.gui.MenuBackdrop;
import com.capy.client.gui.MenuTheme;
import com.capy.client.util.Guard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the title screen's panorama with the client's themed scene and adds
 * the theme picker.
 *
 * <p>{@code renderBackground} is the single call that paints the panorama, the
 * blur and the darkening before any widget exists. The custom scene is drawn
 * there instead; the vanilla logo is cancelled by {@code LogoRendererMixin} so
 * it cannot cover the client wordmark, while the buttons and narration are
 * untouched. The theme button is added at the end of {@code init}, after the
 * vanilla widgets, so it sits above the menu and survives a resize.</p>
 *
 * <p>The widget methods are reached through {@link ScreenAccessor}. They are
 * inherited from {@code Screen}, and a {@code @Shadow} in this mixin is not
 * resolved against superclasses - doing that crashed the game on startup.</p>
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin {
	@Unique
	private Button capy$themeButton;

	@Inject(method = "init", at = @At("TAIL"), require = 0)
	private void capy$initMenu(CallbackInfo ci) {
		try {
			MenuBackdrop.onOpen(this);
			ScreenAccessor screen = (ScreenAccessor) (Object) this;
			// A resize re-runs init on the same TitleScreen. Removing the old
			// button first keeps exactly one picker on screen.
			if (capy$themeButton != null) {
				screen.capy$removeWidget(capy$themeButton);
				capy$themeButton = null;
			}
			int width = Minecraft.getInstance().getWindow().getGuiScaledWidth();
			capy$themeButton = Button.builder(
					Component.literal("Theme: " + MenuTheme.selected().label()), button -> {
						MenuTheme next = MenuTheme.next();
						ClientConfig.get().setMenuTheme(next.id());
						button.setMessage(Component.literal("Theme: " + next.label()));
						// Replay the opening so the new palette is seen from
						// its first frame rather than as a mid-scene recolour.
						MenuBackdrop.restart();
						ClientConfig.saveNow();
					}).bounds(Math.max(4, width - 154), 8, 146, 20).build();
			screen.capy$addRenderableWidget(capy$themeButton);
		} catch (Throwable failure) {
			// A missing picker costs one button; the menu still opens.
			Guard.report("menu theme button", failure);
		}
	}

	@Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true, require = 0)
	private void capy$menuBackdrop(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
			CallbackInfo ci) {
		try {
			if (MenuBackdrop.render(graphics)) {
				ci.cancel();
			}
		} catch (Throwable failure) {
			// A broken backdrop leaves the vanilla one in place.
			Guard.report("title backdrop", failure);
		}
	}
}
