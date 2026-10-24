package com.capy.client.keybind;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Client keybinds, registered into vanilla's Controls screen so they can be
 * rebound in Options -&gt; Controls like any vanilla key.
 *
 * The GUI binding lives in its own "Capy Keybind" category, so it does not mix
 * with the vanilla categories. Minecraft handles the rebinding, the conflict
 * detection and the persistence (in {@code options.txt}) - the client only reads
 * the current binding.
 *
 * This class also owns handing the game its input back after a custom screen
 * closes. That is not optional polish: opening a screen releases every key
 * mapping and ungrabs the mouse, and closing it only restores the <em>toggle</em>
 * keys and re-grabs the mouse. A player who is holding W when the GUI closes
 * would otherwise stand still until they let go and pressed it again, and a
 * player whose window was not focused at that exact instant would be left with
 * the mouse ungrabbed - indistinguishable from a frozen game. The restore is
 * therefore requested by the screen and performed from the tick, over a few
 * ticks, until the mouse is actually grabbed again.
 */
public final class ClientKeybinds {
	/** Category shown in the vanilla Controls screen. */
	private static final Identifier CATEGORY_ID = Identifier.fromNamespaceAndPath("capy", "keybind");

	/** Translation key of the binding. */
	private static final String OPEN_GUI_KEY = "key.capy.open_gui";
	private static final String INSPECT_KEY = "key.capy.inspect_knife";

	private static KeyMapping openClickGui;
	private static KeyMapping inspectKnife;

	/** Ticks left to keep trying to hand input back; negative means idle. */
	private static int restoreTicks = -1;
	/** True once the physical keys were read back into the mappings. */
	private static boolean keysSynced;

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

	/**
	 * Asks for the game's input to be handed back. Called when a custom screen
	 * closes; the work itself happens in {@link #tickInputRestore(Minecraft)}.
	 */
	public static void requestInputRestore() {
		restoreTicks = 60;
		keysSynced = false;
	}

	/**
	 * Hands the keyboard and the mouse back, retrying for up to three seconds.
	 *
	 * Two things are retried, because both can legitimately fail once:
	 *
	 *  - grabbing the mouse, which vanilla refuses while the window is not
	 *    active. A player who alt-tabs at the wrong moment would otherwise come
	 *    back to a game where the camera does not turn;
	 *  - nothing else. The physical keys are read back exactly once: the
	 *    mappings include toggle keys, and re-reading a held toggle key flips
	 *    it, which would turn sprint on and off while the player holds it.
	 */
	public static void tickInputRestore(Minecraft mc) {
		if (restoreTicks < 0) {
			return;
		}
		if (restoreTicks == 0) {
			restoreTicks = -1;
			return;
		}
		restoreTicks--;
		if (mc == null || mc.level == null || mc.player == null || mc.screen != null) {
			return;
		}
		if (!keysSynced) {
			// Opening a screen released every mapping; this is the only thing
			// that reads the held keys back into them.
			KeyMapping.setAll();
			keysSynced = true;
		}
		if (!mc.mouseHandler.isMouseGrabbed()) {
			// grabMouse() is a no-op when the window is not active, which is
			// exactly why this is retried instead of done once.
			mc.mouseHandler.grabMouse();
		}
		if (mc.mouseHandler.isMouseGrabbed()) {
			restoreTicks = -1;
		}
	}

	public static KeyMapping openClickGui() {
		return openClickGui;
	}

	/** The inspect binding, so the GUI can show the current key. */
	public static KeyMapping inspectKnife() {
		return inspectKnife;
	}
}
