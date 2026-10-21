package com.capy.client.module.modules;

import com.capy.client.config.ConfigValues;
import com.capy.client.gui.McCanvas;
import com.capy.client.gui.McType;
import com.capy.client.gui.UiCanvas;
import com.capy.client.gui.UiPetals;
import com.capy.client.gui.UiType;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.Category;
import com.capy.client.module.Configurable;
import com.capy.client.module.Module;
import com.capy.client.module.SliderSetting;
import com.capy.client.module.Tickable;
import com.capy.client.module.WatchlistSetting;
import com.capy.client.module.modules.antiadmin.AdminAlert;
import com.capy.client.module.modules.antiadmin.AdminDetector;
import com.capy.client.module.modules.antiadmin.AlertManager;
import com.capy.client.module.modules.antiadmin.AlertRenderer;
import com.capy.client.module.modules.antiadmin.AlertSound;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Anti Admin — warns the player when a watched staff member shows up nearby.
 *
 * How it works:
 *   <ul>
 *     <li>every few ticks the client looks at the player entities the server
 *         actually sent and looks for names on the watchlist;</li>
 *     <li>a match inside the configured radius raises an alert: the guardian
 *         style face overlay, the corner notification and (optionally) a
 *         sound;</li>
 *     <li>the same player does not alert again until the cooldown has passed,
 *         so one admin walking around cannot spam the screen.</li>
 *   </ul>
 *
 * Honest limits: the module can only see what the server tells the client.
 * A server side vanish system that removes the player from the client
 * entirely cannot be detected from the client, and the module never claims
 * otherwise. Names are matched case-insensitively against the identity the
 * client observes (game profile and player list), so a cross-platform server
 * only matches when it exposes that identity.
 */
public class AntiAdminModule extends Module implements Configurable, Tickable {
	/** Ticks between two scans. 4 ticks is 5 scans per second. */
	public static final int SCAN_INTERVAL_TICKS = 4;

	public static final int MIN_RANGE = 8;
	public static final int MAX_RANGE = 128;
	public static final int DEFAULT_RANGE = 32;

	public static final int MIN_COOLDOWN = 0;
	public static final int MAX_COOLDOWN = 60;
	public static final int DEFAULT_COOLDOWN = 10;

	private final WatchlistSetting watchlist = setting(new WatchlistSetting("watchlist"));
	private final SliderSetting range = setting(new SliderSetting("range", DEFAULT_RANGE, MIN_RANGE, MAX_RANGE));
	private final BooleanSetting faceAlert = setting(new BooleanSetting("face_alert", true));
	private final BooleanSetting notification = setting(new BooleanSetting("notification", true));
	private final BooleanSetting sound = setting(new BooleanSetting("sound", true));
	private final SliderSetting cooldown = setting(new SliderSetting("cooldown", DEFAULT_COOLDOWN, MIN_COOLDOWN, MAX_COOLDOWN));

	/** The typefaces are created once and reused for every frame. */
	private static final UiType TYPE = new McType();

	private final AlertManager alerts = new AlertManager();
	/** The petals that are released when an alert appears. */
	private final UiPetals alertPetals = new UiPetals();
	private int ticksUntilScan;

	public AntiAdminModule() {
		super(Category.MISC, "anti_admin");
	}

	// ------------------------------------------------------------- lifecycle

	@Override
	protected void onToggle(boolean state) {
		if (!state) {
			alerts.reset();
		}
	}

	/** Clears the transient detection state (world change, disconnect). */
	public void reset() {
		alerts.reset();
		ticksUntilScan = 0;
	}

	/**
	 * Runs one detection step. Called from the client tick.
	 */
	@Override
	public void onTick(Minecraft mc) {
		if (!isEnabled()) {
			return;
		}
		if (mc.level == null || mc.player == null) {
			alerts.reset();
			return;
		}
		if (ticksUntilScan > 0) {
			ticksUntilScan--;
			return;
		}
		ticksUntilScan = SCAN_INTERVAL_TICKS - 1;

		long now = System.currentTimeMillis();
		long cooldownMs = Math.max(0, cooldown.get()) * 1000L;
		boolean playSound = sound.value();
		boolean raised = alerts.update(
				AdminDetector.scan(mc, watchlist, detectionRange()),
				cooldownMs,
				playSound ? AlertSound::play : null,
				now);
		if (raised) {
			// A few blossoms leave the portrait as it appears.
			alertPetals.burst(AlertRenderer.PORTRAIT_CX, AlertRenderer.PORTRAIT_CY, 7, 1.0f);
		}
	}

	/** Draws the alerts over the HUD. */
	public void onHudRender(GuiGraphics g, int screenW, int screenH, float deltaTicks) {
		if (!isEnabled()) {
			return;
		}
		// The petals of the alert keep drifting while it is on screen.
		alertPetals.update(deltaTicks, screenW, screenH);
		AdminAlert alert = alerts.active();
		if (alert == null) {
			return;
		}
		float alpha = alert.alpha(System.currentTimeMillis());
		if (alpha <= 0.01f) {
			return;
		}
		UiCanvas canvas = McCanvas.of(g);
		UiType type = TYPE;
		if (faceAlert.value()) {
			AlertRenderer.renderFaceAlert(canvas, type.regular(), type.medium(), type.display(), g,
					alertPetals, screenW, screenH, alert, alpha);
		}
		if (notification.value()) {
			AlertRenderer.renderNotification(canvas, type.regular(), type.medium(), screenW, screenH, alert, alpha);
		}
	}

	// -------------------------------------------------------------- settings

	/** Detection radius in blocks. */
	public int detectionRange() {
		return Math.max(MIN_RANGE, Math.min(MAX_RANGE, range.get()));
	}

	// ---------------------------------------------------------- persistence

	@Override
	public void loadConfig(JsonObject json) {
		watchlist.set(ConfigValues.stringList(json, "watchlist"));
		range.set(clamp(ConfigValues.intValue(json, "range", DEFAULT_RANGE), MIN_RANGE, MAX_RANGE));
		faceAlert.set(ConfigValues.boolValue(json, "face_alert", true));
		notification.set(ConfigValues.boolValue(json, "notification", true));
		sound.set(ConfigValues.boolValue(json, "sound", true));
		cooldown.set(clamp(ConfigValues.intValue(json, "cooldown", DEFAULT_COOLDOWN), MIN_COOLDOWN, MAX_COOLDOWN));
	}

	@Override
	public void saveConfig(JsonObject json) {
		com.google.gson.JsonArray names = new com.google.gson.JsonArray();
		for (String entry : watchlist.entries()) {
			names.add(entry);
		}
		json.add("watchlist", names);
		json.addProperty("range", detectionRange());
		json.addProperty("face_alert", faceAlert.value());
		json.addProperty("notification", notification.value());
		json.addProperty("sound", sound.value());
		json.addProperty("cooldown", Math.max(MIN_COOLDOWN, Math.min(MAX_COOLDOWN, cooldown.get())));
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
