package com.capy.client.module.modules;

import com.capy.client.config.ConfigValues;
import com.capy.client.gui.McCanvas;
import com.capy.client.gui.McType;
import com.capy.client.gui.UiCanvas;
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
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Anti Admin — warns the player when a watched staff member shows up nearby.
 *
 * How it works:
 *   <ul>
 *     <li>every few ticks the client looks at the player entities the server
 *         actually sent and looks for names on the watchlist;</li>
 *     <li>only a player that is really in the world and inside the configured
 *         radius raises a proximity alert. A name that merely shows up in the
 *         player list does not: being connected to the same server is not
 *         being near the player, and the radius cannot be measured from it;</li>
 *     <li>a watched name that appears in the player list <em>after</em> the
 *         client has been on the server is a join, and is announced once. It
 *         is never announced again for that session, even if the same player
 *         comes near, which is what makes the two kinds of alert distinct;</li>
 *     <li>a watched name that <em>leaves</em> the tab list while its account
 *         stays connected has been made invisible with the modern vanish
 *         update, and is announced once. That is the one hidden state the
 *         client can still see, and it is exactly the one this module exists
 *         for;</li>
 *     <li>the alert is the elder guardian effect with the watched player's own
 *         face, then "&lt;name&gt; is watching you" and the sign-off, plus the
 *         corner notification and (optionally) a sound;</li>
 *     <li>the same player does not alert again until the cooldown has passed,
 *         so one player walking around cannot spam the screen.</li>
 *   </ul>
 *
 * Honest limits: the module can only see what the server tells the client.
 * A vanish system that removes the account from the client instead of merely
 * unlisting it, and a player in spectator mode, are not sent to other clients
 * at all, so nothing on the client can detect them - and this module does not
 * pretend to. A vanish is also only noticed at the next scan, so a player who
 * vanishes and returns within the same four ticks is missed. Names
 * are matched case-insensitively against the identity the client observes
 * (game profile and player list), so a cross-platform server only matches
 * when it exposes that identity.
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

	/**
	 * Ticks after joining a world during which new names are treated as part of
	 * the initial player list rather than as arrivals.
	 *
	 * The server sends the tab list for everyone already online right after the
	 * connection is established. Without this window the very first scan would
	 * announce every watched player on the server as if they had all just
	 * joined, which is exactly the false alarm this module exists to avoid.
	 */
	private static final int JOIN_GRACE_TICKS = 40;
	/** Announced events that may wait their turn on screen. */
	private static final int JOIN_QUEUE_MAX = 5;

	private final WatchlistSetting watchlist = setting(new WatchlistSetting("watchlist"));
	private final SliderSetting range = setting(new SliderSetting("range", DEFAULT_RANGE, MIN_RANGE, MAX_RANGE));
	private final BooleanSetting faceAlert = setting(new BooleanSetting("face_alert", true));
	private final BooleanSetting notification = setting(new BooleanSetting("notification", true));
	private final BooleanSetting sound = setting(new BooleanSetting("sound", true));
	private final SliderSetting cooldown = setting(new SliderSetting("cooldown", DEFAULT_COOLDOWN, MIN_COOLDOWN, MAX_COOLDOWN));

	/** The typefaces are created once and reused for every frame. */
	private static final UiType TYPE = new McType();

	/** An announced event waiting for the screen to be free. */
	private record Pending(AdminDetector.Match match, AdminAlert.Kind kind) {
	}

	private final AlertManager alerts = new AlertManager();
	/** UUIDs already listed this session, so one arrival is announced once. */
	private final Set<UUID> joinSeen = new HashSet<>();
	/** UUIDs already reported as vanished this session. */
	private final Set<UUID> vanishSeen = new HashSet<>();
	/** UUIDs listed on the previous scan, used to spot a vanish. */
	private final Set<UUID> listedLast = new HashSet<>();
	/** Announced events waiting for the screen to be free. */
	private final ArrayDeque<Pending> pending = new ArrayDeque<>();
	private int ticksUntilScan;
	/** Ticks since the session started, used for the join grace period. */
	private int ticksSinceSessionStart;

	public AntiAdminModule() {
		super(Category.MISC, "anti_admin");
	}

	// ------------------------------------------------------------- lifecycle

	@Override
	protected void onToggle(boolean state) {
		if (state) {
			// Enabling mid-session must not announce everyone who was already
			// online: clearing the baseline restarts the grace period, which
			// re-learns who is already there without alerting.
			joinSeen.clear();
			vanishSeen.clear();
			listedLast.clear();
			pending.clear();
			ticksSinceSessionStart = 0;
		} else {
			reset();
		}
	}

	/** Clears the transient detection state (world change, disconnect). */
	public void reset() {
		alerts.reset();
		joinSeen.clear();
		vanishSeen.clear();
		listedLast.clear();
		pending.clear();
		ticksUntilScan = 0;
		ticksSinceSessionStart = 0;
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
			reset();
			return;
		}
		ticksSinceSessionStart++;
		if (ticksUntilScan > 0) {
			ticksUntilScan--;
			return;
		}
		ticksUntilScan = SCAN_INTERVAL_TICKS - 1;

		long now = System.currentTimeMillis();
		long cooldownMs = Math.max(0, cooldown.get()) * 1000L;
		boolean playSound = sound.value();

		// Proximity first: being near is the stronger signal, so it takes the
		// screen if both happen during the same scan.
		alerts.update(
				AdminDetector.scan(mc, watchlist, detectionRange()),
				cooldownMs,
				playSound ? AlertSound::play : null,
				now);

		// Join announcements. Every listed player is remembered the first time
		// it is seen, so a single arrival is announced exactly once and a server
		// that rebuilds its tab list cannot re-report the same person.
		List<AdminDetector.Match> joined = AdminDetector.scanJoins(mc, watchlist, joinSeen);

		// Vanish: an account that was listed on the previous scan and is not
		// listed now. Scanning is unconditional so the baseline is current, but
		// nothing is announced until the grace window has passed.
		List<AdminDetector.Match> vanished = AdminDetector.scanVanished(mc, watchlist, listedLast);

		if (ticksSinceSessionStart >= JOIN_GRACE_TICKS) {
			for (AdminDetector.Match match : joined) {
				if (pending.size() >= JOIN_QUEUE_MAX) {
					break;
				}
				pending.add(new Pending(match, AdminAlert.Kind.JOINED));
			}
			// One vanish report per player per session, so a plugin that flaps
			// the listed flag cannot spam the screen.
			for (AdminDetector.Match match : vanished) {
				if (pending.size() >= JOIN_QUEUE_MAX) {
					break;
				}
				if (vanishSeen.add(match.uuid())) {
					pending.add(new Pending(match, AdminAlert.Kind.VANISHED));
				}
			}
		}
		if (!pending.isEmpty() && !alerts.isBusy(now)) {
			Pending next = pending.poll();
			if (next != null && alerts.raise(next.match(), next.kind(), now) && playSound) {
				AlertSound.play();
			}
		}
	}

	/** Draws the alerts over the HUD. */
	public void onHudRender(GuiGraphics g, int screenW, int screenH) {
		if (!isEnabled()) {
			return;
		}
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
			AlertRenderer.renderFaceAlert(canvas, type.medium(), type.display(), g,
					screenW, screenH, alert);
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
