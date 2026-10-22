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
import com.capy.client.module.modules.antiadmin.PacketSignals;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Anti Admin - warns the player when a watched account stops being visible.
 *
 * Six independent signals feed one alert queue, in the order the server makes
 * them available:
 *
 *   <ul>
 *     <li><b>nearby</b> - the entity is in the world and inside the radius.
 *         The only signal with a real distance;</li>
 *     <li><b>ghost</b> - the tab entry was removed but the entity is still in
 *         the world a tick later. A real disconnect removes both;</li>
 *     <li><b>vanished</b> - an {@code UPDATE_LISTED false} packet, read at the
 *         packet level so no transition can fall between two ticks;</li>
 *     <li><b>probed</b> - the entry and the entity are gone, but the server's
 *         own command completion still names the player. Nothing else can see
 *         this case;</li>
 *     <li><b>spectator</b> - listed, but in spectator mode. Vanilla never
 *         sends a spectator's entity to a survival client, so the tab list is
 *         the only place that says so;</li>
 *     <li><b>hidden tag</b> - listed, but on a team that hides nametags.</li>
 *   </ul>
 *
 * Each signal is reported at most once per player per session, so a plugin
 * that flaps the listed flag cannot spam the screen. The last known position
 * is remembered, which is what lets a vanish still say how far away the
 * player was when the server took them.
 *
 * Honest limits: a player in spectator mode is not sent to other clients, and
 * a server that removes the account and the entity leaves nothing behind -
 * the probe is the only thing that can answer for that case, and it is a
 * request the server can see.
 */
public class AntiAdminModule extends Module implements Configurable, Tickable {
	/** Ticks between two world scans. 4 ticks is 5 scans per second. */
	public static final int SCAN_INTERVAL_TICKS = 4;

	public static final int MIN_RANGE = 8;
	public static final int MAX_RANGE = 128;
	public static final int DEFAULT_RANGE = 32;

	public static final int MIN_COOLDOWN = 0;
	public static final int MAX_COOLDOWN = 60;
	public static final int DEFAULT_COOLDOWN = 10;

	/**
	 * Ticks after joining a world during which nothing is announced.
	 *
	 * The server sends the whole tab list right after the connection is
	 * established, so without this window the first scan would announce every
	 * watched player already online as if they had just joined.
	 */
	private static final int JOIN_GRACE_TICKS = 40;
	/** Announced events that may wait their turn on screen. */
	private static final int QUEUE_MAX = 5;
	/** Ticks between two probes: one completion request per second. */
	private static final int PROBE_INTERVAL_TICKS = 20;
	/** Probes waiting their turn. */
	private static final int PROBE_QUEUE_MAX = 32;
	/** How long a probe may stay unanswered before it is abandoned. */
	private static final int PROBE_TIMEOUT_TICKS = 100;
	/** Ticks to wait before deciding whether a removed player is really gone. */
	private static final int GHOST_DELAY_TICKS = 1;
	/** A remembered position older than this is not worth reporting. */
	private static final long LAST_SEEN_MAX_MS = 300_000L;

	private final WatchlistSetting watchlist = setting(new WatchlistSetting("watchlist"));
	private final SliderSetting range = setting(new SliderSetting("range", DEFAULT_RANGE, MIN_RANGE, MAX_RANGE));
	private final BooleanSetting faceAlert = setting(new BooleanSetting("face_alert", true));
	private final BooleanSetting notification = setting(new BooleanSetting("notification", true));
	private final BooleanSetting sound = setting(new BooleanSetting("sound", true));
	private final BooleanSetting probe = setting(new BooleanSetting("probe", true));
	private final SliderSetting cooldown = setting(new SliderSetting("cooldown", DEFAULT_COOLDOWN, MIN_COOLDOWN, MAX_COOLDOWN));

	/** The typefaces are created once and reused for every frame. */
	private static final UiType TYPE = new McType();

	/** An announced event waiting for the screen to be free. */
	private record Pending(AdminDetector.Match match, AdminAlert.Kind kind) {
	}

	/** Where a watched player was, and when. */
	private record Seen(double distance, long atMs) {
	}

	/** A removed player whose entity still has to be looked for. */
	private record Ghost(UUID uuid, String name, int dueTick) {
	}

	/** A completion request waiting to be sent; the UUID is null when unknown. */
	private record Probe(String name, UUID uuid) {
	}

	private final AlertManager alerts = new AlertManager();
	/** UUIDs already listed this session, so one arrival is announced once. */
	private final Set<UUID> joinSeen = new HashSet<>();
	/** Signals already announced per player, so each is reported once. */
	private final Map<UUID, Set<AdminAlert.Kind>> reported = new HashMap<>();
	/** Announced events waiting for the screen to be free. */
	private final ArrayDeque<Pending> pending = new ArrayDeque<>();
	/** Last known distance per watched player, kept after they disappear. */
	private final Map<UUID, Seen> lastSeen = new HashMap<>();
	/** Removed players whose entity has not been checked yet. */
	private final List<Ghost> ghosts = new ArrayList<>();
	private final ArrayDeque<Probe> probes = new ArrayDeque<>();
	/** Names already queued for a probe, so one player is asked about once. */
	private final Set<String> probeAsked = new HashSet<>();
	private Probe probeInFlight;
	private int probeId = -1;
	private int probeSentTick;
	private int probeCooldown;
	private int ticksUntilScan;
	/** Ticks since the session started, used for the join grace period. */
	private int ticksSinceSessionStart;
	private int tickCounter;
	private boolean watchlistProbed;

	public AntiAdminModule() {
		super(Category.MISC, "anti_admin");
	}

	// ------------------------------------------------------------- lifecycle

	@Override
	protected void onToggle(boolean state) {
		// Enabling mid-session must not announce everyone who was already
		// online, and disabling must not leave stale signals behind.
		reset();
	}

	/** Clears the transient detection state (world change, disconnect). */
	public void reset() {
		alerts.reset();
		PacketSignals.clear();
		joinSeen.clear();
		reported.clear();
		pending.clear();
		lastSeen.clear();
		ghosts.clear();
		probes.clear();
		probeAsked.clear();
		probeInFlight = null;
		probeId = -1;
		probeCooldown = 0;
		ticksUntilScan = 0;
		ticksSinceSessionStart = 0;
		watchlistProbed = false;
	}

	/** Runs one detection step. Called from the client tick. */
	@Override
	public void onTick(Minecraft mc) {
		if (!isEnabled()) {
			return;
		}
		if (mc.level == null || mc.player == null) {
			reset();
			return;
		}
		tickCounter++;
		ticksSinceSessionStart++;

		// Draining every tick is what makes the packet signals instant.
		List<PacketSignals.Event> events = PacketSignals.drainEvents();
		List<PacketSignals.ProbeResponse> responses = PacketSignals.drainResponses();

		if (ticksUntilScan > 0) {
			ticksUntilScan--;
		} else {
			ticksUntilScan = SCAN_INTERVAL_TICKS - 1;
			scan(mc);
		}

		handleEvents(events);
		handleGhosts(mc);
		handleResponses(responses);
		pumpProbes();

		long now = System.currentTimeMillis();
		if (!pending.isEmpty() && !alerts.isBusy(now)) {
			Pending next = pending.poll();
			if (next != null && alerts.raise(next.match(), next.kind(), now) && sound.value()) {
				AlertSound.play();
			}
		}
	}

	// ------------------------------------------------------------------ scan

	/** The periodic half: everything that can be read from the world and the tab list. */
	private void scan(Minecraft mc) {
		long now = System.currentTimeMillis();
		lastSeen.values().removeIf(seen -> now - seen.atMs() > LAST_SEEN_MAX_MS);

		double maxDistance = detectionRange();
		List<AdminDetector.Match> inRange = new ArrayList<>();
		for (AdminDetector.Match match : AdminDetector.scan(mc, watchlist)) {
			lastSeen.put(match.uuid(), new Seen(match.distance(), now));
			if (match.distance() <= maxDistance) {
				inRange.add(match);
			}
		}
		alerts.update(inRange, Math.max(0, cooldown.get()) * 1000L,
				sound.value() ? AlertSound::play : null, now);

		for (AdminDetector.Match joined : AdminDetector.scanJoins(mc, watchlist, joinSeen)) {
			enqueue(joined, AdminAlert.Kind.JOINED);
		}

		for (AdminDetector.TabTell tell : AdminDetector.scanTells(mc, watchlist)) {
			AdminAlert.Kind kind = tell.tell() == AdminDetector.Tell.SPECTATOR
					? AdminAlert.Kind.SPECTATOR
					: AdminAlert.Kind.HIDDEN_TAG;
			enqueue(tell.match(), kind);
		}

		// Once per session, ask about every watched name that is not online at
		// all: a player hidden before the connection was made leaves no trace
		// anywhere else.
		if (!watchlistProbed && probe.value() && ticksSinceSessionStart >= JOIN_GRACE_TICKS) {
			watchlistProbed = true;
			seedProbes(mc);
		}
	}

	// ----------------------------------------------------------- packet level

	private void handleEvents(List<PacketSignals.Event> events) {
		for (PacketSignals.Event event : events) {
			String name = event.name();
			if (name == null || !watchlist.matches(name)) {
				continue;
			}
			switch (event.kind()) {
				case UNLISTED -> enqueue(withLastSeen(event.uuid(), name), AdminAlert.Kind.VANISHED);
				case REMOVED -> {
					// The entity can outlive the entry; the next tick decides.
					ghosts.add(new Ghost(event.uuid(), name, tickCounter + GHOST_DELAY_TICKS));
					queueProbe(name, event.uuid());
				}
			}
		}
	}

	private void handleGhosts(Minecraft mc) {
		for (Iterator<Ghost> it = ghosts.iterator(); it.hasNext();) {
			Ghost ghost = it.next();
			if (tickCounter < ghost.dueTick()) {
				continue;
			}
			it.remove();
			if (mc.level.getPlayerByUUID(ghost.uuid()) != null) {
				enqueue(withLastSeen(ghost.uuid(), ghost.name()), AdminAlert.Kind.GHOST);
			}
		}
	}

	// ---------------------------------------------------------------- probing

	private void seedProbes(Minecraft mc) {
		Set<String> online = new HashSet<>();
		if (mc.getConnection() != null) {
			for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
				String name = info.getProfile() == null ? null : info.getProfile().name();
				if (name != null) {
					online.add(name.toLowerCase(Locale.ROOT));
				}
			}
		}
		for (String entry : watchlist.entries()) {
			if (!online.contains(entry.toLowerCase(Locale.ROOT))) {
				queueProbe(entry, null);
			}
		}
	}

	private void queueProbe(String name, UUID uuid) {
		if (!probe.value() || probes.size() >= PROBE_QUEUE_MAX) {
			return;
		}
		if (probeAsked.add(name.toLowerCase(Locale.ROOT))) {
			probes.add(new Probe(name, uuid));
		}
	}

	private void pumpProbes() {
		if (probeId >= 0) {
			// A server that never answers must not stall the queue forever.
			if (tickCounter - probeSentTick > PROBE_TIMEOUT_TICKS) {
				probeId = -1;
				probeInFlight = null;
			}
			return;
		}
		if (!probe.value()) {
			probes.clear();
			return;
		}
		if (probeCooldown > 0) {
			probeCooldown--;
			return;
		}
		Probe next = probes.poll();
		if (next == null) {
			return;
		}
		probeId = PacketSignals.probe(next.name());
		probeInFlight = probeId < 0 ? null : next;
		probeSentTick = tickCounter;
		probeCooldown = PROBE_INTERVAL_TICKS;
	}

	private void handleResponses(List<PacketSignals.ProbeResponse> responses) {
		for (PacketSignals.ProbeResponse response : responses) {
			if (probeId < 0 || response.id() != probeId) {
				continue;
			}
			Probe asked = probeInFlight;
			probeId = -1;
			probeInFlight = null;
			if (asked == null) {
				return;
			}
			for (String name : response.names()) {
				if (name.equalsIgnoreCase(asked.name())) {
					enqueue(probedMatch(asked), AdminAlert.Kind.PROBED);
					return;
				}
			}
			return;
		}
	}

	// ----------------------------------------------------------------- alerts

	/** Queues an alert, unless this signal was already reported for that player. */
	private void enqueue(AdminDetector.Match match, AdminAlert.Kind kind) {
		if (match == null || ticksSinceSessionStart < JOIN_GRACE_TICKS || pending.size() >= QUEUE_MAX) {
			return;
		}
		Set<AdminAlert.Kind> seen = reported.computeIfAbsent(match.uuid(),
				key -> EnumSet.noneOf(AdminAlert.Kind.class));
		// A player already reported as hidden is not reported again because a
		// later packet hid them harder; the screen has already said it.
		if (kind == AdminAlert.Kind.PROBED
				&& (seen.contains(AdminAlert.Kind.VANISHED) || seen.contains(AdminAlert.Kind.GHOST))) {
			return;
		}
		if (seen.add(kind)) {
			pending.add(new Pending(match, kind));
		}
	}

	/** The match for a player the client can no longer see. */
	private AdminDetector.Match withLastSeen(UUID uuid, String name) {
		Seen seen = lastSeen.get(uuid);
		double distance = seen == null ? -1.0 : seen.distance();
		long age = seen == null ? -1L : System.currentTimeMillis() - seen.atMs();
		return new AdminDetector.Match(uuid, name, name, distance, age, skinOf(uuid));
	}

	/** The match for a player the server named but the client cannot see at all. */
	private AdminDetector.Match probedMatch(Probe probe) {
		UUID uuid = probe.uuid() != null
				? probe.uuid()
				: UUID.nameUUIDFromBytes(probe.name().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
		return withLastSeen(uuid, probe.name());
	}

	private static PlayerSkin skinOf(UUID uuid) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getConnection() == null) {
			return null;
		}
		PlayerInfo info = mc.getConnection().getPlayerInfo(uuid);
		return info == null ? null : info.getSkin();
	}

	// -------------------------------------------------------------- rendering

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
		probe.set(ConfigValues.boolValue(json, "probe", true));
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
		json.addProperty("probe", probe.value());
		json.addProperty("cooldown", Math.max(MIN_COOLDOWN, Math.min(MAX_COOLDOWN, cooldown.get())));
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
