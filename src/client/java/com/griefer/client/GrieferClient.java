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
 */
public class GrieferClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ModuleManager.get().register(new SwingSpeedModule());
		ClientKeybinds.register();

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (ClientKeybinds.consumeOpenGuiClick()) {
				Minecraft mc = Minecraft.getInstance();
				mc.setScreen(new ClickGuiScreen());
			}
		});
	}
}
