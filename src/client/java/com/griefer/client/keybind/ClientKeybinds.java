package com.griefer.client.keybind;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Client keybinds, registered into vanilla's Controls screen so they can be
 * rebound in Options -> Controls like any vanilla key.
 */
public final class ClientKeybinds {
	private static final Identifier CATEGORY_ID = Identifier.fromNamespaceAndPath("griefer", "main");

	private static KeyMapping openClickGui;

	private ClientKeybinds() {
	}

	public static void register() {
		KeyMapping.Category category = KeyMapping.Category.register(CATEGORY_ID);
		openClickGui = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.griefer.openClickGui",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_RIGHT_SHIFT,
				category
		));
	}

	/** True on the tick after the key was pressed (edge-triggered, works in-game). */
	public static boolean consumeOpenGuiClick() {
		boolean clicked = false;
		while (openClickGui.consumeClick()) {
			clicked = true;
		}
		return clicked;
	}

	public static KeyMapping openClickGui() {
		return openClickGui;
	}
}
