package com.capy.client;

import com.capy.client.config.ClientConfig;
import com.capy.client.gui.ClickGuiScreen;
import com.capy.client.gui.UiFonts;
import com.capy.client.gui.UiTheme;
import com.capy.client.keybind.ClientKeybinds;
import com.capy.client.module.Module;
import com.capy.client.module.ModuleManager;
import com.capy.client.module.Tickable;
import com.capy.client.module.modules.AntiAdminModule;
import com.capy.client.module.modules.CustomKnifeModule;
import com.capy.client.module.modules.SwingSpeedModule;
import com.capy.client.module.modules.WeatherNotifierModule;
import com.capy.client.module.modules.hud.HudElements;
import com.capy.client.module.modules.hud.HudRenderer;
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
		modules.register(new CustomKnifeModule());
		WeatherNotifierModule weather = new WeatherNotifierModule();
		modules.register(weather);
		AntiAdminModule antiAdmin = new AntiAdminModule();
		modules.register(antiAdmin);
		// The HUD is a set of independent elements, not one panel.
		HudElements.register(modules);

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
			// Build the bundled faces outside a frame. The lazy path would
			// otherwise upload a font texture in the middle of the first GUI or
			// HUD draw, which is the worst possible moment for it.
			UiFonts.warmUp();
			// The click counter is drained every tick, so a press that happens
			// while a screen is open is dropped instead of queued.
			boolean clicked = ClientKeybinds.consumeOpenGuiClick();
			if (clicked && client.screen == null) {
				client.setScreen(new ClickGuiScreen());
			}
			// A finished window must never stay attached: a screen that has
			// played its outro but is still set would keep swallowing every
			// key and click while painting nothing.
			if (client.screen instanceof ClickGuiScreen gui && gui.isFinished()) {
				client.setScreen(null);
				ClientKeybinds.requestInputRestore();
			}
			// Hands the mouse and the held keys back after the GUI closes,
			// retrying while the window is unfocused.
			ClientKeybinds.tickInputRestore(client);
			// The inspect key is drained every tick for the same reason, and
			// only acted on while the player is actually in the world.
			boolean inspect = ClientKeybinds.consumeInspectClick();
			if (inspect && client.screen == null) {
				CustomKnifeModule knife = modules.get(CustomKnifeModule.class);
				if (knife != null) {
					knife.triggerInspect();
				}
			}
			for (Module module : modules.modules()) {
				if (module instanceof Tickable tickable) {
					tickable.onTick(client);
				}
			}
		});

		// The HUD chips go first, so an alert is never hidden behind them.
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("capy", "hud"),
				(graphics, deltaTracker) -> HudRenderer.render(graphics, graphics.guiWidth(), graphics.guiHeight()));

		// The weather card is drawn over the HUD chips and under the alerts.
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("capy", "weather_toast"),
				(graphics, deltaTracker) -> weather.onHudRender(graphics, graphics.guiWidth()));

		// The Anti Admin alerts are drawn over the HUD.
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("capy", "anti_admin"),
				(graphics, deltaTracker) -> antiAdmin.onHudRender(graphics, graphics.guiWidth(),
						graphics.guiHeight()));

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
