package com.capy.client;

import com.capy.client.config.ClientConfig;
import com.capy.client.gui.ClickGuiScreen;
import com.capy.client.gui.UiTheme;
import com.capy.client.keybind.ClientKeybinds;
import com.capy.client.module.Module;
import com.capy.client.module.ModuleManager;
import com.capy.client.module.Tickable;
import com.capy.client.module.modules.AntiAdminModule;
import com.capy.client.module.modules.HudModule;
import com.capy.client.module.modules.SwingSpeedModule;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;

/**
 * Client entrypoint: registers the modules, restores the saved settings,
 * registers the keybind and wires the tick, HUD and connection hooks.
 *
 * The open/close keybind (default Right Shift, rebindable in the vanilla
 * Controls screen under "Capy Keybind") is a single edge-triggered listener
 * — no duplicate handlers, nothing runs while another screen is open.
 */
public class CapyClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ModuleManager modules = ModuleManager.get();
		modules.register(new SwingSpeedModule());
		AntiAdminModule antiAdmin = new AntiAdminModule();
		modules.register(antiAdmin);
		HudModule hud = new HudModule();
		modules.register(hud);

		// Harden stored settings once after registration (clamps invalid
		// config values before anything can read them).
		SwingSpeedModule swingSpeed = modules.get(SwingSpeedModule.class);
		if (swingSpeed != null) {
			swingSpeed.sanitize();
		}

		// Restore the persisted configuration: the GUI theme and the settings
		// of every module that keeps state.
		ClientConfig config = ClientConfig.load(FabricLoader.getInstance().getConfigDir());
		UiTheme.setPalette(config.theme());

		ClientKeybinds.register();

		// One tick listener drives the keybind and every tickable module.
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// The click counter is drained every tick, so a press that happens
			// while a screen is open is dropped instead of queued.
			boolean clicked = ClientKeybinds.consumeOpenGuiClick();
			if (clicked && client.screen == null) {
				client.setScreen(new ClickGuiScreen());
			}
			for (Module module : modules.modules()) {
				if (module instanceof Tickable tickable) {
					tickable.onTick(client);
				}
			}
		});

		// The Anti Admin alerts are drawn over the HUD.
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("capy", "anti_admin"),
				(graphics, deltaTracker) -> antiAdmin.onHudRender(graphics, graphics.guiWidth(),
						graphics.guiHeight(), deltaTracker.getGameTimeDeltaPartialTick(false)));

		// The optional information panel.
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("capy", "hud"),
				(graphics, deltaTracker) -> hud.onHudRender(graphics, graphics.guiWidth(), graphics.guiHeight()));

		// Transient detection state must not survive a world change or a
		// disconnect: the players of the old world are gone.
		ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register((before, after) -> antiAdmin.reset());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			antiAdmin.reset();
			// Leaving a server is a good moment to store the settings.
			ClientConfig.get().save(FabricLoader.getInstance().getConfigDir());
		});
	}
}
