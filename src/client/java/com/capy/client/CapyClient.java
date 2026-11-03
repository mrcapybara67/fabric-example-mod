package com.capy.client;

import com.capy.client.config.ClientConfig;
import com.capy.client.gui.ClickGuiScreen;
import com.capy.client.gui.GuiColors;
import com.capy.client.gui.HudEditorScreen;
import com.capy.client.gui.UiFonts;
import com.capy.client.gltf.GltfReloadListener;
import com.capy.client.gui.UiTheme;
import com.capy.client.keybind.ClientKeybinds;
import com.capy.client.module.Module;
import com.capy.client.module.ModuleManager;
import com.capy.client.module.Tickable;
import com.capy.client.module.modules.AntiAdminModule;
import com.capy.client.module.modules.AntiCheatNotifierModule;
import com.capy.client.module.modules.BlockAnimationModule;
import com.capy.client.module.modules.BlockOverlayModule;
import com.capy.client.module.modules.CustomFOVModule;
import com.capy.client.module.modules.CustomKnifeModule;
import com.capy.client.module.modules.CustomSkyModule;
import com.capy.client.module.modules.FullbrightModule;
import com.capy.client.module.modules.HandAuraModule;
import com.capy.client.module.modules.SmoothCameraModule;
import com.capy.client.module.modules.SwingSpeedModule;
import com.capy.client.module.modules.WeatherNotifierModule;
import com.capy.client.module.modules.esp.AmethystFinderModule;
import com.capy.client.module.modules.esp.BlockEspModule;
import com.capy.client.module.modules.esp.EspRenderer;
import com.capy.client.module.modules.esp.PlayerEspModule;
import com.capy.client.module.modules.esp.StorageEspModule;
import com.capy.client.module.modules.hud.HudElements;
import com.capy.client.module.modules.hud.HudRenderer;
import com.capy.client.util.Guard;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;

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
		// The notifier sits beside Anti Admin: both modules answer a question
		// about the server rather than about the world, and neither owns a
		// watchlist.
		AntiCheatNotifierModule antiCheatNotifier = new AntiCheatNotifierModule();
		modules.register(antiCheatNotifier);
		// The three world overlays. They share one renderer and one set of
		// drawing rules, so what they highlight differs but how they look
		// never does.
		modules.register(new PlayerEspModule());
		modules.register(new StorageEspModule());
		modules.register(new BlockEspModule());
		// The finder is not an overlay: it searches the world for a geode and
		// marks what it found, which is why it lives in its own category.
		modules.register(new AmethystFinderModule());
		// The visual set: the sky, the light level, the camera, the field of
		// view, and the two overlays that frame what the player is looking at.
		modules.register(new CustomSkyModule());
		modules.register(new FullbrightModule());
		modules.register(new SmoothCameraModule());
		modules.register(new CustomFOVModule());
		modules.register(new BlockOverlayModule());
		modules.register(new HandAuraModule());
		modules.register(new BlockAnimationModule());
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
		GuiColors.load(config.guiAccent(), config.radialAccent());

		ClientKeybinds.register();

		// The knife meshes are data, so a resource pack that replaces one has to
		// be able to change it without restarting the game. The listener drops
		// the parsed meshes on every reload; the next draw reads them again.
		ResourceManagerHelper.get(PackType.CLIENT_RESOURCES)
				.registerReloadListener(new GltfReloadListener());

		// One tick listener drives the keybind and every tickable module.
		//
		// The body is guarded as a whole, not only piece by piece: it opens
		// screens, drains keybinds and restores input, and all of that runs
		// inside vanilla's tick loop, where an escaped exception is a crash
		// rather than a skipped feature.
		ClientTickEvents.END_CLIENT_TICK.register(client -> Guard.run("tick", () -> {
			// Build the bundled faces outside a frame. The lazy path would
			// otherwise upload a font texture in the middle of the first GUI or
			// HUD draw, which is the worst possible moment for it.
			// Guarded: this is the client tick, where an escaped exception is the
			// game dying the moment the player joins a world.
			Guard.run("fonts", UiFonts::warmUp);
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
			Guard.run("input restore", () -> ClientKeybinds.tickInputRestore(client));
			// The inspect key is drained every tick for the same reason, and
			// only acted on while the player is actually in the world.
			// The HUD editor key. It opens over the world rather than as a page in
			// the click GUI, because placing a chip needs the scene behind it: the
			// whole question is whether the readout is readable there.
			boolean editHud = ClientKeybinds.consumeHudEditorClick();
			if (editHud && client.screen == null && client.player != null) {
				client.setScreen(new HudEditorScreen());
			}
			boolean inspect = ClientKeybinds.consumeInspectClick();
			if (inspect && client.screen == null) {
				CustomKnifeModule knife = modules.get(CustomKnifeModule.class);
				if (knife != null) {
					knife.triggerInspect();
				}
			}
			for (Module module : modules.modules()) {
				if (module instanceof Tickable tickable) {
					// One module throwing must not stop the other modules from
					// ticking - and it must never reach vanilla's tick loop, where
					// it would be a crash.
					Guard.run("tick " + module.id(), () -> tickable.onTick(client));
				}
			}
		}));

		// The world overlays are drawn after entities, inside the main world
		// pass, and before translucent terrain. This point is load bearing in
		// 1.21.11: the debug-render event is fired while the frame graph is
		// still being built, so immediate geometry submitted there is discarded
		// and the ESP appears to do nothing. AFTER_ENTITIES runs inside the
		// pass, where vanilla is already submitting entity geometry through the
		// same buffer source mechanism.
		//
		// Each overlay then decides for itself whether a wall may hide it: the
		// see-through pair of render types has no depth test, so a box behind
		// terrain is still drawn. Drawing here is safe for that: the terrain
		// depth buffer is complete by this point, the boxes ignore it, and they
		// write no depth either, so nothing drawn later is clipped by an
		// occluder that is not on screen.
		// Guarded: this runs inside the world pass, where a thrown exception is a
		// crash on the first frame in a world rather than a missing box.
		WorldRenderEvents.AFTER_ENTITIES.register(context -> Guard.run("esp", () -> EspRenderer.render(context)));

		// The HUD chips go first, so an alert is never hidden behind them.
		// Every HUD element is guarded. The HUD is drawn on the first frame in a
		// world, so an exception in one chip is otherwise a crash on join, and
		// there is nothing a player can do about it.
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("capy", "hud"),
				(graphics, deltaTracker) -> Guard.run("hud",
						() -> HudRenderer.render(graphics, graphics.guiWidth(), graphics.guiHeight())));

		// The weather card is drawn over the HUD chips and under the alerts.
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("capy", "weather_toast"),
				(graphics, deltaTracker) -> Guard.run("weather card",
						() -> weather.onHudRender(graphics, graphics.guiWidth())));

		// The Anti Admin alerts are drawn over the HUD.
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("capy", "anti_admin"),
				(graphics, deltaTracker) -> Guard.run("anti-admin alerts",
						() -> antiAdmin.onHudRender(graphics, graphics.guiWidth(), graphics.guiHeight())));

		// The anticheat banner sits under the alerts: it is a note about the
		// server, never a warning about a player.
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("capy", "anticheat_notifier"),
				(graphics, deltaTracker) -> Guard.run("anticheat banner",
						() -> antiCheatNotifier.onHudRender(graphics, graphics.guiWidth(), graphics.guiHeight())));

		// The hand brackets are registered here, once, rather than from the
		// module's toggle. A HUD id can only be added once, so adding it on every
		// enable made the second enable throw inside the click handler that
		// switched the module on - the game died on the second toggle. The
		// renderer gates itself on isEnabled(), so this costs one early return
		// per frame while the module is off.
		HandAuraModule handAura = modules.get(HandAuraModule.class);
		if (handAura != null) {
			HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("capy", "hand_aura"),
					(graphics, deltaTracker) -> Guard.run("hand aura",
							() -> handAura.onHudRender(graphics, deltaTracker)));
		}

		// Transient detection state must not survive a world change or a
		// disconnect: the players of the old world are gone.
		ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register((before, after) ->
				Guard.run("anti-admin reset", antiAdmin::reset));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			Guard.run("anti-admin disconnect", () -> {
				antiAdmin.reset();
				// Leaving a server is a good moment to store the settings.
				ClientConfig.get().save(FabricLoader.getInstance().getConfigDir());
			});
		});
	}
}
