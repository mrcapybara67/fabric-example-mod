package com.capy.client.module.modules;

import com.capy.client.config.ConfigValues;
import com.capy.client.gui.McCanvas;
import com.capy.client.gui.McType;
import com.capy.client.gui.UiCanvas;
import com.capy.client.gui.UiType;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.Category;
import com.capy.client.module.ChoiceSetting;
import com.capy.client.module.Configurable;
import com.capy.client.module.Module;
import com.capy.client.module.SliderSetting;
import com.capy.client.module.StringSetting;
import com.capy.client.module.Tickable;
import com.capy.client.module.WatchlistSetting;
import com.capy.client.module.modules.antiadmin.AdminAlert;
import com.capy.client.module.modules.antiadmin.AdminDetector;
import com.capy.client.module.modules.antiadmin.AlertManager;
import com.capy.client.module.modules.antiadmin.AlertRecord;
import com.capy.client.module.modules.antiadmin.AlertRenderer;
import com.capy.client.module.modules.antiadmin.AlertSound;
import com.capy.client.module.modules.antiadmin.AntiAdminHudRenderer;
import com.capy.client.module.modules.antiadmin.AntiAdminWebhook;
import com.capy.client.module.modules.antiadmin.BlockInteractDetector;
import com.capy.client.module.modules.antiadmin.ChatDetector;
import com.capy.client.module.modules.antiadmin.Confidence;
import com.capy.client.module.modules.antiadmin.ContainerDetector;
import com.capy.client.module.modules.antiadmin.Correlator;
import com.capy.client.module.modules.antiadmin.DetectionKind;
import com.capy.client.module.modules.antiadmin.EntityTabCrossRefDetector;
import com.capy.client.module.modules.antiadmin.ExplosionDetector;
import com.capy.client.module.modules.antiadmin.GamemodeDetector;
import com.capy.client.module.modules.antiadmin.GhostUiDetector;
import com.capy.client.module.modules.antiadmin.KnownPlayerStore;
import com.capy.client.module.modules.antiadmin.NpcFingerprinter;
import com.capy.client.module.modules.antiadmin.Observation;
import com.capy.client.module.modules.antiadmin.PacketSignals;
import com.capy.client.module.modules.antiadmin.ParticleDetector;
import com.capy.client.module.modules.antiadmin.PingGhostDetector;
import com.capy.client.module.modules.antiadmin.ProbeDetector;
import com.capy.client.module.modules.antiadmin.RapidPacketDetector;
import com.capy.client.module.modules.antiadmin.ServerProfile;
import com.capy.client.module.modules.antiadmin.ServerProfileMatcher;
import com.capy.client.module.modules.antiadmin.ShadowMapDetector;
import com.capy.client.module.modules.antiadmin.SignalBuffer;
import com.capy.client.module.modules.antiadmin.SignalDetector;
import com.capy.client.module.modules.antiadmin.SoundDetector;
import com.capy.client.module.modules.antiadmin.TeamPacketDetector;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;

/**
 * Anti Admin: watches the accounts on your watchlist and the traces a vanished
 * player leaves behind.
 *
 * The module is the wiring, not the detection. Signals are collected by the
 * detectors into a rolling buffer, scored once per tick by the correlator, and
 * shown by the manager; this class only decides who is enabled, which profile
 * is in force and where the output goes.
 *
 * Two sources feed it, deliberately kept apart:
 * <ul>
 *   <li>the <b>watchlist</b>, which is about accounts the user named — a
 *       sighting is reported directly, because being told a watched player is
 *       standing next to you does not need a correlation;</li>
 *   <li>the <b>signal layer</b>, which is about accounts nobody named — it
 *       reports what the server did, not who did it.</li>
 * </ul>
 *
 * Ghost mode silences the screen and the sound but keeps the webhook, which is
 * the whole point of running this on a server where a visible alert would be a
 * liability.
 */
public class AntiAdminModule extends Module implements Configurable, Tickable {
	public static final int MIN_RANGE = 8;
	public static final int MAX_RANGE = 128;
	public static final int DEFAULT_RANGE = 32;
	/** The watchlist scan is a player loop; twice a second is plenty. */
	private static final int SCAN_INTERVAL = 10;
	/** The profile is re-resolved this often, in ticks. */
	private static final int PROFILE_INTERVAL = 40;

	private final WatchlistSetting watchlist = setting(new WatchlistSetting("watchlist"));
	private final SliderSetting range = setting(new SliderSetting("range", DEFAULT_RANGE, MIN_RANGE, MAX_RANGE));
	private final BooleanSetting faceAlert = setting(new BooleanSetting("face_alert", true));
	private final BooleanSetting notification = setting(new BooleanSetting("notification", true));
	private final BooleanSetting sound = setting(new BooleanSetting("sound", true));
	private final BooleanSetting probe = setting(new BooleanSetting("probe", true));
	private final BooleanSetting ghostMode = setting(new BooleanSetting("ghost_mode", false));
	private final ChoiceSetting threshold =
			setting(new ChoiceSetting("alert_threshold", 1, "low", "medium", "high", "certain"));
	private final ChoiceSetting profileChoice =
			setting(new ChoiceSetting("profile", 0, "auto", "default", "donut-family", "anarchy"));
	private final StringSetting webhookUrl = setting(new StringSetting("webhook", ""));

	/** One switch per signal, in enum order so the GUI list is stable. */
	private final Map<DetectionKind, BooleanSetting> signalToggles = new EnumMap<>(DetectionKind.class);
	private final List<SignalDetector> detectors = new ArrayList<>();
	private final Set<UUID> npcs = new HashSet<>();

	private final SignalBuffer buffer = new SignalBuffer();
	private final AlertManager alerts = new AlertManager();
	private final KnownPlayerStore known = new KnownPlayerStore();
	private final ServerProfileMatcher matcher = new ServerProfileMatcher();
	private final AntiAdminWebhook webhook = new AntiAdminWebhook("");
	private final AntiAdminHudRenderer hud = new AntiAdminHudRenderer();
	private final Correlator correlator = new Correlator(buffer, alerts, known, npcs);

	private ServerProfile profile = ServerProfile.DEFAULT;
	private String host = "unknown";
	/** The URL the sender is currently pointed at, so a new one is noticed. */
	private String appliedWebhook = "";
	private long tick;
	private int scanCountdown;
	private int profileCountdown;
	private boolean loaded;

	public AntiAdminModule() {
		super(Category.MISC, "anti_admin");
		for (DetectionKind kind : DetectionKind.values()) {
			signalToggles.put(kind, setting(new BooleanSetting(kind.settingKey(), true)));
		}

		NpcFingerprinter fingerprinter = new NpcFingerprinter(buffer, npcs);
		detectors.add(new EntityTabCrossRefDetector(buffer, npcs));
		detectors.add(fingerprinter);
		detectors.add(new ParticleDetector(buffer));
		detectors.add(new ExplosionDetector(buffer));
		detectors.add(new SoundDetector(buffer));
		detectors.add(new BlockInteractDetector(buffer));
		detectors.add(new ChatDetector(buffer));
		detectors.add(new ProbeDetector(buffer));
		detectors.add(new PingGhostDetector(buffer));
		detectors.add(new TeamPacketDetector(buffer));
		detectors.add(new GamemodeDetector(buffer));
		detectors.add(new RapidPacketDetector(buffer));
		detectors.add(new ShadowMapDetector(buffer));
		detectors.add(new ContainerDetector(buffer));
		detectors.add(new GhostUiDetector(buffer));

		alerts.sink(this::deliver);
	}

	// ------------------------------------------------------------- lifecycle

	@Override
	protected void onToggle(boolean state) {
		if (state) {
			if (!loaded) {
				loaded = true;
				known.load();
			}
			known.bind(host);
			resolveProfile(Minecraft.getInstance());
			for (SignalDetector detector : detectors) {
				detector.onEnable();
			}
		} else {
			for (SignalDetector detector : detectors) {
				detector.onDisable();
			}
			known.save();
			clear();
		}
	}

	/** Drops per-session state. Called on a world change and on a disconnect. */
	public void reset() {
		known.save();
		clear();
	}

	private void clear() {
		buffer.clear();
		alerts.reset();
		hud.clear();
		npcs.clear();
		PacketSignals.clear();
		tick = 0L;
		scanCountdown = 0;
		profileCountdown = 0;
		for (SignalDetector detector : detectors) {
			detector.reset();
		}
	}

	// ------------------------------------------------------------------- tick

	@Override
	public void onTick(Minecraft mc) {
		if (!isEnabled()) {
			return;
		}
		if (mc == null || mc.level == null || mc.player == null) {
			if (tick != 0L) {
				clear();
			}
			return;
		}
		tick++;
		buffer.tick(tick);
		syncWebhook();
		if (--profileCountdown <= 0) {
			profileCountdown = PROFILE_INTERVAL;
			resolveProfile(mc);
		}
		for (PacketSignals.Event event : PacketSignals.drain()) {
			for (SignalDetector detector : detectors) {
				if (active(detector)) {
					detector.onPacket(event);
				}
			}
		}
		for (SignalDetector detector : detectors) {
			if (active(detector)) {
				detector.onTick(mc);
			}
		}
		correlator.tick(mc, profile, minimumConfidence(), host);
		if (--scanCountdown <= 0) {
			scanCountdown = SCAN_INTERVAL;
			scanWatchlist(mc);
		}
	}

	/** Reports every watched player the client can see inside the range. */
	private void scanWatchlist(Minecraft mc) {
		for (AdminDetector.Match match : AdminDetector.scan(mc, watchlist)) {
			if (match.distance() > detectionRange()) {
				continue;
			}
			AlertRecord record = new AlertRecord(match.uuid(), match.username(), Confidence.HIGH,
					List.<Observation>of(), System.currentTimeMillis(),
					positionOf(mc, match.uuid()), match.distance(), host, AdminAlert.Kind.NEARBY);
			alerts.raise(record);
		}
	}

	/** Where a watched player is standing right now, or null when gone. */
	private static BlockPos positionOf(Minecraft mc, UUID uuid) {
		net.minecraft.world.entity.Entity entity = mc.level == null ? null : mc.level.getPlayerByUUID(uuid);
		return entity == null ? null : entity.blockPosition();
	}

	/** Re-resolves the profile for the current host and bind the store to it. */
	private void resolveProfile(Minecraft mc) {
		String current = ServerProfileMatcher.hostOf(mc);
		host = current == null ? "singleplayer" : current;
		ServerProfile resolved = matcher.resolve(mc, profileChoice.option());
		if (resolved != profile || !known.host().equals(host)) {
			profile = resolved;
			known.bind(host);
		}
	}

	// ----------------------------------------------------------------- output

	/**
	 * Everything that happens when an alert is accepted: the card, the sound and
	 * the webhook. Ghost mode keeps only the webhook.
	 */
	private void deliver(AlertRecord record) {
		syncWebhook();
		if (!ghostMode.value()) {
			hud.push(record, System.currentTimeMillis());
			if (sound.value() && record.confidence().atLeast(Confidence.HIGH)) {
				AlertSound.play();
			}
		}
		webhook.send(record);
	}

	public void onHudRender(GuiGraphics graphics, int screenW, int screenH) {
		if (!isEnabled() || ghostMode.value()) {
			return;
		}
		AlertManager manager = alerts;
		AdminAlert alert = manager.active();
		if (alert != null) {
			float alpha = alert.alpha(System.currentTimeMillis());
			if (alpha > 0.01f) {
				UiCanvas canvas = McCanvas.of(graphics);
				UiType type = new McType();
				if (faceAlert.value()) {
					AlertRenderer.renderFaceAlert(canvas, type, graphics, screenW, screenH, alert);
				}
				if (notification.value()) {
					AlertRenderer.renderNotification(canvas, type, screenW, screenH, alert, alpha);
				}
			}
		}
		hud.render(graphics, screenW, screenH);
	}

	// --------------------------------------------------------------- settings

	/** True when the per-signal switch allows this detector to run. */
	private boolean signalOn(DetectionKind kind) {
		BooleanSetting toggle = signalToggles.get(kind);
		return toggle != null && toggle.value();
	}

	/** A detector runs when its own switch is on and the module permits it. */
	private boolean active(SignalDetector detector) {
		if (!signalOn(detector.kind())) {
			return false;
		}
		// The probe is the only detector that sends anything to the server, so
		// it also answers to its own kill switch.
		return !(detector instanceof ProbeDetector) || probingAllowed();
	}

	public int detectionRange() {
		return Math.max(MIN_RANGE, Math.min(MAX_RANGE, range.get()));
	}

	/** The lowest confidence that produces an alert. */
	public Confidence minimumConfidence() {
		return switch (threshold.option()) {
			case "low" -> Confidence.LOW;
			case "high" -> Confidence.HIGH;
			case "certain" -> Confidence.CERTAIN;
			default -> Confidence.MEDIUM;
		};
	}

	/** The profile in force, exposed for the debug readout. */
	public ServerProfile activeProfile() {
		return profile;
	}

	@Override
	public void loadConfig(JsonObject json) {
		watchlist.set(ConfigValues.stringList(json, "watchlist"));
		range.set(ConfigValues.intValue(json, "range", DEFAULT_RANGE));
		faceAlert.set(ConfigValues.boolValue(json, "face_alert", true));
		notification.set(ConfigValues.boolValue(json, "notification", true));
		sound.set(ConfigValues.boolValue(json, "sound", true));
		probe.set(ConfigValues.boolValue(json, "probe", true));
		ghostMode.set(ConfigValues.boolValue(json, "ghost_mode", false));
		threshold.select(ConfigValues.stringValue(json, "alert_threshold", "medium"));
		profileChoice.select(ConfigValues.stringValue(json, "profile", ServerProfile.AUTO));
		webhookUrl.set(ConfigValues.stringValue(json, "webhook", ""));
		syncWebhook();
		for (Map.Entry<DetectionKind, BooleanSetting> entry : signalToggles.entrySet()) {
			entry.getValue().set(ConfigValues.boolValue(json, entry.getKey().settingKey(), true));
		}
	}

	@Override
	public void saveConfig(JsonObject json) {
		JsonArray names = new JsonArray();
		for (String entry : watchlist.entries()) {
			names.add(entry);
		}
		json.add("watchlist", names);
		json.addProperty("range", detectionRange());
		json.addProperty("face_alert", faceAlert.value());
		json.addProperty("notification", notification.value());
		json.addProperty("sound", sound.value());
		json.addProperty("probe", probe.value());
		json.addProperty("ghost_mode", ghostMode.value());
		json.addProperty("alert_threshold", threshold.option());
		json.addProperty("profile", profileChoice.option());
		json.addProperty("webhook", webhookUrl.value());
		for (Map.Entry<DetectionKind, BooleanSetting> entry : signalToggles.entrySet()) {
			json.addProperty(entry.getKey().settingKey(), entry.getValue().value());
		}
	}

	/**
	 * Points the sender at the URL currently in the setting.
	 *
	 * The setting can change while the module is running (the webhook field in
	 * the GUI), so the sender is re-read every tick rather than only when the
	 * config is loaded. The comparison is a string equals, and it only calls
	 * through when the text actually differs.
	 */
	private void syncWebhook() {
		String url = webhookUrl.value();
		if (!url.equals(appliedWebhook)) {
			appliedWebhook = url;
			webhook.configure(url);
		}
	}

	/** True when the probe detector is allowed to run. */
	public boolean probingAllowed() {
		return probe.value();
	}

	/** A one line state summary, used by the log when a session starts. */
	public String summary() {
		return String.format(Locale.ROOT, "%s · %s · %d watched · %d signals on",
				host, profile.name(), watchlist.entries().size(),
				signalToggles.values().stream().filter(BooleanSetting::value).count());
	}
}
