package com.capy.client.mixin;

import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Invokes the protected widget methods on {@link Screen}.
 *
 * <p>A mixin on {@code TitleScreen} cannot {@code @Shadow} these: both methods
 * live on the superclass, and Mixin only resolves shadows declared by the
 * target class itself. The first build crashed on exactly that - the shadow
 * descriptor was remapped, the method was looked up in {@code TitleScreen}, and
 * it was not there. An invoker on the declaring class is the supported route.</p>
 */
@Mixin(Screen.class)
public interface ScreenAccessor {
	@Invoker("addRenderableWidget")
	GuiEventListener capy$addRenderableWidget(GuiEventListener widget);

	@Invoker("removeWidget")
	void capy$removeWidget(GuiEventListener widget);
}
