package com.capy.client;

import com.capy.client.config.ClientConfig;
import com.capy.client.gui.ClickGuiScreen;
import com.capy.client.gui.GuiColors;
import com.capy.client.gui.HudEditorScreen;
import com.capy.client.gui.UiFonts;
import com.capy.client.gui.UiTheme;
import com.capy.client.keybind.ClientKeybinds;
import com.capy.client.mixin.CapyMixinPlugin;
import com.capy.client.module.Module;
import com.capy.client.module.ModuleManager;
import com.capy.client.module.Tickable;
import com.capy.client.module.modules.AntiAdminModule;
import com.capy.client.module.modules.AntiCheatNotifierModule;
import com.capy.client.module.modules.AtmosphereModule;
import com.capy.client.module.modules.AtmosphereSkyRenderer;
import com.capy.client.module.modules.AtmosphereWorldRenderer;
import com.capy.client.module.modules.BlockAnimationModule;
import com.capy.client.module.modules.BlockOverlayModule;
import com.capy.client.module.modules.BoatFlyModule;
import com.capy.client.module.modules.BoatNoclipModule;
import com.capy.client.module.modules.CustomFOVModule;
import com.capy.client.module.modules.FlyModule;
import com.capy.client.module.modules.FreecamModule;
import com.capy.client.module.modules.FreelookModule;
import com.capy.client.module.modules.FullbrightModule;
import com.capy.client.module.modules.ItemInspectModule;
import com.capy.client.module.modules.HandAuraModule;
import com.capy.client.module.modules.SmoothCameraModule;
import com.capy.client.module.modules.StaffListModule;
import com.capy.client.module.modules.SwingSpeedModule;
import com.capy.client.module.modules.WeatherNotifierModule;
import com.capy.client.module.modules.esp.AmethystFinderModule;
import com.capy.client.module.modules.esp.BlockEspModule;
import com.capy.client.module.modules.esp.ChunkFinderBetterModule;
import com.capy.client.module.modules.esp.ChunkFinderModule;
import com.capy.client.module.modules.esp.ChunkPlateRenderer;
import com.capy.client.module.modules.esp.EspRenderer;
import com.capy.client.module.modules.esp.SusChunkFinderModule;
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
		Guard.run("mixin audit", CapyMixinPlugin::audit);
		modules.register(new SwingSpeedModule());
		modules.register(new ItemInspectModule());
		WeatherNotifierModule weather = new WeatherNotifierModule();
		modules.register(weather);
		// The movement modules: the body flies, or the boat does. Both read
		// the ordinary movement keys and both keep vanilla's own packet path,
		// which is what keeps the server's movement checks inside their limits.
		modules.register(new FlyModule());
		modules.register(new BoatFlyModule());
		modules.register(new BoatNoclipModule());
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
		// The three read-only finders share the plate renderer, scan queue,
		// heat map and per-server calibration. They never send a packet.
		modules.register(new SusChunkFinderModule());
		modules.register(new ChunkFinderBetterModule());
		modules.register(new ChunkFinderModule());
		// The visual set: the atmosphere, the light level, the camera, the field
		// of view, and the two overlays that frame what the player is looking at.
		// The atmosphere changes what vanilla renders rather than painting over
		// it, so it is the sky, the fog, the weather and the light in one module.
		modules.register(new AtmosphereModule());
		modules.register(new FullbrightModule());
		modules.register(new SmoothCameraModule());
		modules.register(new CustomFOVModule());
		modules.register(new BlockOverlayModule());
		modules.register(new HandAuraModule());
		modules.register(new BlockAnimationModule());
		// Freecam detaches the camera from the body: a viewer's tool for
		// inspecting a build or a base without walking over to it.
		modules.register(new FreecamModule());
		// Freelook is the short-leash companion: orbit the body without leaving
		// it, while the body keeps its own heading and interaction point.
		modules.register(new FreelookModule());
		// The staff list is the display half of a watchlist: a glass box of the
		// accounts the player named, placed wherever they want it.
		modules.register(new StaffListModule());
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
		GuiColors.load(GuiColors.DEFAULT_WINDOW);

		ClientKeybinds.register();

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
			// Freecam's own key. Toggling rather than opening anything: the point
			// of the module is to look around while flying, and the moment you
			// want it is the moment you are moving. Rebound in Controls, so the
			// key shown on the module card is the key that works.
			boolean freecamKey = ClientKeybinds.consumeFreecamClick();
			if (freecamKey && client.screen == null && client.player != null) {
				FreecamModule freecamModule = modules.get(FreecamModule.class);
				if (freecamModule != null) {
					freecamModule.toggle();
				}
			}
			boolean freelookKey = ClientKeybinds.consumeFreelookClick();
			if (freelookKey && client.screen == null && client.player != null) {
				FreelookModule freelookModule = modules.get(FreelookModule.class);
				if (freelookModule != null) {
					freelookModule.toggle();
				}
			}
			boolean inspect = ClientKeybinds.consumeInspectClick();
			if (inspect && client.screen == null) {
				ItemInspectModule itemInspect = modules.get(ItemInspectModule.class);
				if (itemInspect != null) {
					itemInspect.triggerInspect();
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
		WorldRenderEvents.AFTER_ENTITIES.register(
				context -> Guard.run("chunk plates", () -> ChunkPlateRenderer.render(context)));
		// The skybox is submitted before terrain so every block can cover it.
		// Its render type owns no depth state, which is what keeps distant
		// terrain visible while the vanilla panorama is replaced.
		WorldRenderEvents.START_MAIN.register(
				context -> Guard.run("atmosphere sky", () -> AtmosphereSkyRenderer.render(context)));
		// Atmosphere's geometry sits in the same pass as the overlays, after
		// entities: the petals and light shafts belong to the world's depth
		// buffer, not to a screen-space layer.
		WorldRenderEvents.AFTER_ENTITIES.register(
				context -> Guard.run("atmosphere world", () -> AtmosphereWorldRenderer.render(context)));

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
		StaffListModule staffList = modules.get(StaffListModule.class);
		ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register((before, after) -> {
			Guard.run("atmosphere world reset", AtmosphereWorldRenderer::reset);
			Guard.run("finder world reset", () -> {
				ChunkPlateRenderer.reset();
				com.capy.client.module.modules.esp.LightSignalQueue.clear();
				resetFinders();
			});
			Guard.run("anti-admin reset", antiAdmin::reset);
			Guard.run("staff-list reset", () -> {
				if (staffList != null) {
					staffList.resetSession();
				}
			});
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			Guard.run("atmosphere world disconnect", AtmosphereWorldRenderer::reset);
			Guard.run("finder disconnect", () -> {
				ChunkPlateRenderer.reset();
				com.capy.client.module.modules.esp.LightSignalQueue.clear();
				resetFinders();
				com.capy.client.module.modules.movement.MovementKickGuard.resetSession();
			});
			Guard.run("anti-admin disconnect", () -> {
				antiAdmin.reset();
				if (staffList != null) {
					staffList.resetSession();
				}
				// Leaving a server is a good moment to store the settings.
				ClientConfig.get().save(FabricLoader.getInstance().getConfigDir());
			});
		});
	}

	private static void resetFinders() {
		for (Module module : ModuleManager.get().modules()) {
			if (module instanceof com.capy.client.module.modules.esp.BaseFinderModule finder) {
				finder.resetSession();
			}
		}
	}
}
