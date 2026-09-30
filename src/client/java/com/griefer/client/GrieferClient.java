package com.griefer.client;

import com.griefer.client.gui.ClickGuiScreen;
import com.griefer.client.keybind.ClientKeybinds;
import com.griefer.client.module.ModuleManager;
import com.griefer.client.module.modules.SwingSpeedModule;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/**
 * Client entrypoint: registers modules, keybinds and the tick hook that
 * opens the ClickGUI.
 *
 * The open/close keybind (default Right Shift, rebindable in the vanilla
 * Controls screen) is a single edge-triggered listener — no duplicate
 * handlers, nothing runs while the GUI is closed.
 */
public class GrieferClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ModuleManager modules = ModuleManager.get();
		modules.register(new SwingSpeedModule());

		// Harden stored settings once after registration (clamps invalid
		// config values before anything can read them).
		SwingSpeedModule swingSpeed = modules.get(SwingSpeedModule.class);
		if (swingSpeed != null) {
			swingSpeed.sanitize();
		}

		ClientKeybinds.register();

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (ClientKeybinds.consumeOpenGuiClick()) {
				Minecraft mc = Minecraft.getInstance();
				if (mc.screen == null) {
					mc.setScreen(new ClickGuiScreen());
				} else if (mc.screen instanceof ClickGuiScreen gui) {
					gui.requestClose();
				}
			}
		});
	}
}
