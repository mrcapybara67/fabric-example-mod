package com.capy.client.keybind;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Client keybinds, registered into vanilla's Controls screen so they can be
 * rebound in Options -&gt; Controls like any vanilla key.
 *
 * The GUI binding lives in its own "Capy Keybind" category, so it does not
 * mix with the vanilla categories. Minecraft handles the rebinding, the
 * conflict detection and the persistence (in {@code options.txt}) — the client
 * only reads the current binding.
 */
public final class ClientKeybinds {
	/** Category shown in the vanilla Controls screen. */
	private static final Identifier CATEGORY_ID = Identifier.fromNamespaceAndPath("capy", "keybind");

	/** Translation key of the binding. */
	private static final String OPEN_GUI_KEY = "key.capy.open_gui";
	private static final String INSPECT_KEY = "key.capy.inspect_knife";

	private static KeyMapping openClickGui;
	private static KeyMapping inspectKnife;

	private ClientKeybinds() {
	}

	public static void register() {
		KeyMapping.Category category = KeyMapping.Category.register(CATEGORY_ID);
		openClickGui = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				OPEN_GUI_KEY,
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_RIGHT_SHIFT,
				category
		));
		inspectKnife = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				INSPECT_KEY,
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_C,
				category
		));
	}

	/**
	 * True on the tick after the key was pressed (edge-triggered, works
	 * in-game). The click counter is always drained, so a press that happens
	 * while a screen is open cannot be replayed later.
	 */
	public static boolean consumeOpenGuiClick() {
		boolean clicked = false;
		while (openClickGui != null && openClickGui.consumeClick()) {
			clicked = true;
		}
		return clicked;
	}

	/**
	 * True on the tick after the inspect key was pressed. Drained even when it
	 * is ignored, so a press made with a menu open is dropped rather than
	 * replayed the next time the player is in the world.
	 */
	public static boolean consumeInspectClick() {
		boolean clicked = false;
		while (inspectKnife != null && inspectKnife.consumeClick()) {
			clicked = true;
		}
		return clicked;
	}

	public static KeyMapping openClickGui() {
		return openClickGui;
	}

	/** The inspect binding, so the GUI can show the current key. */
	public static KeyMapping inspectKnife() {
		return inspectKnife;
	}
}
