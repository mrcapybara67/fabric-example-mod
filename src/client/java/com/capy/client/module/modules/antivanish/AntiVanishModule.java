package com.capy.client.module.modules.antivanish;

import com.capy.client.antivanish.VanishPackets;
import com.capy.client.antivanish.VanishProbe;
import com.capy.client.antivanish.core.AlertTimeline;
import com.capy.client.antivanish.core.DepartureTracker;
import com.capy.client.antivanish.core.GhostMemory;
import com.capy.client.antivanish.core.SoundGate;
import com.capy.client.antivanish.core.VanishEngine;
import com.capy.client.antivanish.core.VanishText;
import com.capy.client.config.ConfigValues;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.Category;
import com.capy.client.module.Configurable;
import com.capy.client.module.Module;
import com.capy.client.module.SliderSetting;
import com.capy.client.module.Tickable;
import com.capy.client.module.WatchlistSetting;
import com.capy.client.module.modules.hud.HudElementModule;
import com.capy.client.module.modules.hud.HudPanel;
import com.capy.client.module.modules.antiadmin.PacketSignals;
import com.capy.client.module.modules.antiadmin.TabList;
import com.capy.client.util.Guard;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/**
 * Anti Vanish watches the accounts the user named for the shapes a /vanish
 * leaves in the client's own data.
 *
 * <p>Nothing here sends a packet. Player info, chat and entity metadata are
 * already delivered to the client; this module only remembers them. Weak
 * clues are fused by {@link VanishEngine}, while {@link DepartureTracker}
 * handles the "disappeared from TAB" timing.</p>
 */
public final class AntiVanishModule extends Module implements Configurable, Tickable, HudPanel {
	/** An account must be listed this long before a disappearance is trusted. */
	public static final long LISTED_GRACE_MS = 1_500L;

	private static final int INVISIBLE_SCAN_INTERVAL = 5;
	private static final int HUD_ROWS = 4;
	private static final long ALERT_MS = Math.round(AlertTimeline.TOTAL * 1000.0);
	private static final long PROBE_LEAVE_GRACE_MS = 8_000L;
	private static final double INVISIBLE_RANGE = 64.0;
	private static final int VANISH_WEIGHT = 100;
	private static final int INVISIBLE_WEIGHT = 35;
	private static final long VANISH_COOLDOWN_MS = 3_000L;
	private static final long INVISIBLE_COOLDOWN_MS = 5_000L;
	private static final int SPECTATOR = GameType.SPECTATOR.getId();

	private final WatchlistSetting watchlist = setting(new WatchlistSetting("watchlist"));
	private final BooleanSetting chatLog = setting(new BooleanSetting("chat_log", false));
	private final BooleanSetting probe = setting(new BooleanSetting("probe", false));
	private final SliderSetting anchor = setting(new SliderSetting("anchor", HudElementModule.ANCHOR_TOP_RIGHT,
			HudElementModule.ANCHOR_TOP_LEFT, HudElementModule.ANCHOR_BOTTOM_RIGHT));
	private final SliderSetting offsetX = setting(new SliderSetting("offset_x", 0,
			-HudElementModule.MAX_NUDGE, HudElementModule.MAX_NUDGE));
	private final SliderSetting offsetY = setting(new SliderSetting("offset_y", 0,
			-HudElementModule.MAX_NUDGE, HudElementModule.MAX_NUDGE));
	private final VanishProbe probeClient = new VanishProbe();
	private final Map<String, Long> leaveAt = new HashMap<>();
	private final Map<UUID, Subject> subjects = new HashMap<>();
	private final Map<UUID, Integer> gameModes = new HashMap<>();
	private final VanishEngine engine = new VanishEngine();
	private final DepartureTracker tracker = new DepartureTracker();
	private final GhostMemory ghost = new GhostMemory();
	private VanishEngine.Critical lastCritical;
	private VanishEngine.Detection lastClusterDetection;
	private double lastClusterX;
	private double lastClusterY;
	private double lastClusterZ;
	private ActiveAlert activeAlert;
	private Set<String> lastChatRows = Set.of();
	private boolean seeded;
	private long ticks;

	public AntiVanishModule() {
		super(Category.MISC, "anti_vanish");
	}

	private record Subject(String name, long listedSinceMs, boolean listed) {
	}

	@Override
	protected void onToggle(boolean state) {
		reset();
		if (state) {
			seed(Minecraft.getInstance());
		}
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!isEnabled()) {
			return;
		}
		if (mc == null || mc.level == null || mc.player == null || mc.getConnection() == null) {
			resetDetection();
			resetSubjects();
			return;
		}
		if (!seeded) {
			seed(mc);
		}
		long now = System.currentTimeMillis();
		boolean bodyData = false;
		for (VanishPackets.Event event : VanishPackets.drain()) {
			if (event instanceof VanishPackets.InfoBatch batch) {
				process(batch, mc);
			} else if (event instanceof VanishPackets.Chat chat) {
				processChat(chat.line());
			} else if (event instanceof VanishPackets.BodyData) {
				bodyData = true;
			} else if (event instanceof VanishPackets.Action action) {
				ghost.action(action.timeMs());
			} else if (event instanceof VanishPackets.Container container) {
				ghost.container(container.timeMs());
			} else if (event instanceof VanishPackets.Explosion explosion) {
				ghost.addExplosion(explosion.x(), explosion.y(), explosion.z(), explosion.radius(), explosion.timeMs());
			} else if (event instanceof VanishPackets.PlaceAttempt attempt) {
				ghost.placeAttempt(attempt.blockId(),
						new GhostMemory.Pos(attempt.x(), attempt.y(), attempt.z()), attempt.timeMs());
			} else if (event instanceof VanishPackets.BlockUpdate update) {
				processBlockUpdate(update, mc);
			} else if (event instanceof VanishPackets.Sound sound) {
				processSound(sound, mc);
			} else if (event instanceof VanishPackets.EntitySound sound) {
				processEntitySound(sound, mc);
			} else if (event instanceof VanishPackets.Particle particle) {
				processParticle(particle, mc);
			} else if (event instanceof VanishPackets.Suggestions suggestions) {
				processSuggestions(suggestions);
			}
		}
		ticks++;
		if (ticks % INVISIBLE_SCAN_INTERVAL == 0L || bodyData) {
			scanInvisibleBodies(mc, now);
		}
		processDue(mc, now);
		if (activeAlert != null && now - activeAlert.startedAt() > ALERT_MS) {
			activeAlert = null;
		}
		VanishEngine.Critical critical = engine.evaluate(now);
		if (critical != null) {
			lastCritical = critical;
			if (activeAlert == null) {
				int distance = critical.proof() == null ? -1 : critical.proof().distance();
				activeAlert = new ActiveAlert(critical.name(), distance, now);
				playAlertSound();
			}
		}
		VanishEngine.Cluster cluster = engine.evaluateCluster(now);
		if (cluster != null) {
			lastClusterDetection = new VanishEngine.Detection("Hidden presence",
					String.format(Locale.ROOT, "hidden presence near %.1f, %.1f, %.1f",
							cluster.x(), cluster.y(), cluster.z()),
					cluster.score(), now + VanishEngine.TTL_MS);
			lastClusterX = cluster.x();
			lastClusterY = cluster.y();
			lastClusterZ = cluster.z();
		}
		probeClient.setNames(watchlist.entries());
		probeClient.tick(probe.value(), mc, now);
		updateChatLog(mc, now);
	}

	// --------------------------------------------------------------- packets

	private void process(VanishPackets.InfoBatch batch, Minecraft mc) {
		for (PacketSignals.InfoRemove removal : batch.removals()) {
			processRemove(removal.uuid(), removal.name(), batch.entries(), batch.timeMs());
		}
		for (PacketSignals.InfoUpdate update : batch.updates()) {
			UUID id = update.uuid();
			if (id == null) {
				continue;
			}
			String name = update.name() == null ? nameOf(id) : update.name();
			if (update.gameMode() != null) {
				boolean credibleBefore = credible(id, name, batch.timeMs());
				applyGameMode(id, name, update.gameMode(), credibleBefore, bodyDistance(mc, id), batch.timeMs());
			}
			if (update.listed() != null) {
				processListed(id, name, update.listed(), batch.entries(), batch.timeMs());
			}
		}
	}

	/**
	 * Handles one player-info removal. A packet that removes four or more
	 * accounts is a server/session sweep, not a single vanish; the subject map
	 * is still cleaned so it cannot hold names from the old session.
	 */
	public void processRemove(UUID uuid, String name, int entries, long now) {
		if (uuid == null) {
			return;
		}
		String resolved = name == null ? nameOf(uuid) : name;
		if (entries >= 4) {
			forgetSubject(uuid);
			return;
		}
		if (resolved != null && credible(uuid, resolved, now)) {
			tracker.tabRemoved(uuid.toString(), resolved, now);
		}
		// Keep the name so a leave line arriving after the removal can still
		// cancel the pending finding and teach the tracker that this server
		// sends normal logout messages.
		observeListed(uuid, resolved, false, now);
	}

	/** Handles the listed flag of one player-info update. */
	public void processListed(UUID uuid, String name, boolean listed, int entries, long now) {
		if (uuid == null) {
			return;
		}
		String resolved = name == null ? nameOf(uuid) : name;
		boolean credibleBefore = credible(uuid, resolved, now);
		if (!listed && entries < 4 && credibleBefore && resolved != null) {
			DepartureTracker.Finding finding = tracker.hiddenFromTab(uuid.toString(), resolved, now);
			if (finding != null) {
				addVanish(finding, now);
			}
		}
		observeListed(uuid, resolved, listed, now);
	}

	private void applyGameMode(UUID uuid, String name, int mode, boolean credibleBefore, int bodyDistance, long now) {
		Integer previous = gameModes.put(uuid, mode);
		if (name == null) {
			return;
		}
		if (mode == SPECTATOR) {
			if (credibleBefore) {
				VanishEngine.Proof proof = bodyDistance >= 0
						? new VanishEngine.Proof(uuid.toString(), bodyDistance) : null;
				engine.add(VanishEngine.Type.VANISH, name, proof,
						"Vanish Event: switched to spectator", VANISH_WEIGHT, VANISH_COOLDOWN_MS, now);
			}
		} else if (previous != null && previous == SPECTATOR) {
			engine.forgetVanish(name);
		}
	}

	/** Handles a game-mode update without requiring a live Minecraft instance. */
	public void observeGameMode(UUID uuid, String name, int mode, int bodyDistance, long now) {
		if (uuid == null) {
			return;
		}
		String resolved = name == null ? nameOf(uuid) : name;
		applyGameMode(uuid, resolved, mode, credible(uuid, resolved, now), bodyDistance, now);
	}

	private void processChat(PacketSignals.SystemChat line) {
		if (line == null || line.text() == null) {
			return;
		}
		processChat(line.text(), line.timeMs());
	}

	/** Handles one system chat line without requiring a live Minecraft instance. */
	public void processChat(String text, long now) {
		if (text == null) {
			return;
		}
		List<String[]> known = knownPairs();
		tracker.chat(text, known, now);
		for (String[] pair : known) {
			if (pair.length > 1 && VanishText.looksLikeLeave(text, pair[1])) {
				leaveAt.put(pair[1].toLowerCase(Locale.ROOT), now);
				engine.forgetVanish(pair[1]);
			}
		}
		// A probe target may have no player-info entry left at all, so the
		// watchlist itself has to be checked for its leave line too.
		for (String watched : watchlist.entries()) {
			if (VanishText.looksLikeLeave(text, watched)) {
				leaveAt.put(watched.toLowerCase(Locale.ROOT), now);
				engine.forgetVanish(watched);
			}
		}
		if (probe.value() && looksRateLimited(text)) {
			probeClient.rateLimited(now);
		}
	}

	private static boolean looksRateLimited(String text) {
		if (text == null || text.isBlank()) {
			return false;
		}
		String message = text.toLowerCase(Locale.ROOT);
		return message.contains("rate limit") || message.contains("ratelimit")
				|| message.contains("too many requests") || message.contains("too many commands")
				|| message.contains("slow down") || message.contains("command cooldown")
				|| message.contains("try again later") || message.contains("kicked")
				|| (message.contains("spam") && message.contains("kick"));
	}

	private void processDue(Minecraft mc, long now) {
		processDue(now, new MinecraftView(mc));
	}

	/** Runs the departure delay against a caller-supplied view. */
	public void processDue(long now, DepartureTracker.View view) {
		for (DepartureTracker.Finding finding : tracker.due(now, view)) {
			addVanish(finding, now);
		}
	}

	private void processBlockUpdate(VanishPackets.BlockUpdate update, Minecraft mc) {
		GhostMemory.Pos pos = new GhostMemory.Pos(update.x(), update.y(), update.z());
		ghost.blockUpdate(update.blockId(), pos, update.timeMs());
		if (update.mechanism() && poweredAt(mc, pos)) {
			ghost.markPowered(pos, update.timeMs());
		}
	}

	private void processSound(VanishPackets.Sound sound, Minecraft mc) {
		if (sound == null || !SoundGate.suspiciousSound(sound.id())) {
			return;
		}
		SoundGate.Context context = soundContext(mc, sound.id(), sound.x(), sound.y(), sound.z(), sound.timeMs());
		if (!SoundGate.soundTriggers(context)) {
			return;
		}
		engine.add(VanishEngine.Type.SOUND, label(mc, sound.x(), sound.y(), sound.z()), null,
				"Suspicious Sound: " + SoundGate.idPath(sound.id()), 14, VANISH_COOLDOWN_MS, sound.timeMs());
		engine.addLocated(VanishEngine.Type.SOUND, sound.x(), sound.y(), sound.z(), 14, sound.timeMs());
	}

	private void processEntitySound(VanishPackets.EntitySound sound, Minecraft mc) {
		if (sound == null || mc == null || mc.level == null || mc.player == null) {
			return;
		}
		Entity entity = mc.level.getEntity(sound.entityId());
		if (!(entity instanceof AbstractClientPlayer player) || player == mc.player || !player.isInvisible()) {
			return;
		}
		double distanceSqr = player.distanceToSqr(mc.player);
		if (distanceSqr > 64.0 * 64.0) {
			return;
		}
		String name = player.getGameProfile() == null ? nameOf(player.getUUID())
				: player.getGameProfile().name();
		if (name == null || !credible(player.getUUID(), name, sound.timeMs())) {
			return;
		}
		int distance = (int) Math.round(Math.sqrt(distanceSqr));
		engine.add(VanishEngine.Type.SOUND, name, new VanishEngine.Proof(player.getUUID().toString(), distance),
				"Suspicious Sound: invisible source", 16, VANISH_COOLDOWN_MS, sound.timeMs());
	}

	private void processSuggestions(VanishPackets.Suggestions suggestions) {
		if (!probe.value() || suggestions == null) {
			return;
		}
		String name = probeClient.acceptResponse(suggestions.id(), suggestions.names(), suggestions.timeMs());
		if (name == null) {
			return;
		}
		Long left = leaveAt.get(name.toLowerCase(Locale.ROOT));
		if (left != null && suggestions.timeMs() - left <= PROBE_LEAVE_GRACE_MS) {
			return;
		}
		engine.add(VanishEngine.Type.VANISH, name, null,
				"Vanish Event: hidden but targetable", 90, VANISH_COOLDOWN_MS, suggestions.timeMs());
	}

	private void processParticle(VanishPackets.Particle particle, Minecraft mc) {
		if (particle == null || !SoundGate.suspiciousParticle(particle.id())) {
			return;
		}
		String path = SoundGate.idPath(particle.id());
		boolean ambient = path.contains("smoke") && ambientSource(mc, particle.x(), particle.y(), particle.z());
		boolean trigger = SoundGate.particleTriggers(particle.id(), nearMe(mc, particle.x(), particle.y(), particle.z()),
				visibleCause(mc, particle.x(), particle.y(), particle.z()),
				ghost.explosionRelated(particle.x(), particle.y(), particle.z(), particle.timeMs()),
				ghost.msSinceAction(particle.timeMs()), ambient,
				withinTwoAndAHalfBlocks(mc, particle.x(), particle.y(), particle.z()),
				ghost.bursts(), particle.timeMs());
		if (!trigger) {
			return;
		}
		engine.add(VanishEngine.Type.PARTICLE, label(mc, particle.x(), particle.y(), particle.z()), null,
				"Ghost Particle: " + path, 16, VANISH_COOLDOWN_MS, particle.timeMs());
		engine.addLocated(VanishEngine.Type.PARTICLE, particle.x(), particle.y(), particle.z(), 16, particle.timeMs());
	}

	private static String label(Minecraft mc, double x, double y, double z) {
		if (mc == null || mc.player == null) {
			return "0m here";
		}
		double dx = x - mc.player.getX();
		double dz = z - mc.player.getZ();
		int distance = (int) Math.round(Math.sqrt(mc.player.distanceToSqr(x, y, z)));
		return distance + "m " + compass(dx, dz);
	}

	private static String compass(double dx, double dz) {
		if (Math.abs(dx) < 0.5 && Math.abs(dz) < 0.5) {
			return "here";
		}
		String northSouth = dz < 0.0 ? "N" : dz > 0.0 ? "S" : "";
		String eastWest = dx > 0.0 ? "E" : dx < 0.0 ? "W" : "";
		return northSouth + eastWest;
	}

	private void addVanish(DepartureTracker.Finding finding, long now) {
		VanishEngine.Proof proof = finding.distance() >= 0
				? new VanishEngine.Proof(finding.uuid(), finding.distance())
				: null;
		engine.add(VanishEngine.Type.VANISH, finding.name(), proof, finding.reason(),
				finding.score(), VANISH_COOLDOWN_MS, now);
	}

	// ------------------------------------------------------------- invisible

	private void scanInvisibleBodies(Minecraft mc, long now) {
		if (mc == null || mc.level == null || mc.player == null) {
			return;
		}
		double rangeSqr = INVISIBLE_RANGE * INVISIBLE_RANGE;
		for (AbstractClientPlayer player : mc.level.players()) {
			if (player == null || player == mc.player || player.isRemoved() || !player.isInvisible()) {
				continue;
			}
			double distanceSqr = player.distanceToSqr(mc.player);
			if (distanceSqr > rangeSqr) {
				continue;
			}
			String name = player.getGameProfile() == null ? nameOf(player.getUUID())
					: player.getGameProfile().name();
			if (name == null || !credible(player.getUUID(), name, now)) {
				continue;
			}
			int distance = (int) Math.round(Math.sqrt(distanceSqr));
			observeInvisibleBody(player.getUUID(), name, distance, now);
		}
	}

	/** Adds an invisible-body clue when the account is credible and in range. */
	public void observeInvisibleBody(UUID uuid, String name, int distance, long now) {
		if (uuid == null || name == null || distance < 0 || distance > INVISIBLE_RANGE) {
			return;
		}
		if (!credible(uuid, name, now)) {
			return;
		}
		engine.add(VanishEngine.Type.INVISIBLE, name, new VanishEngine.Proof(uuid.toString(), distance),
				"Invisible Entity: metadata flag", INVISIBLE_WEIGHT, INVISIBLE_COOLDOWN_MS, now);
	}

	private int bodyDistance(Minecraft mc, UUID uuid) {
		if (mc == null || mc.level == null || mc.player == null || uuid == null) {
			return -1;
		}
		Entity entity = mc.level.getPlayerByUUID(uuid);
		if (entity == null || entity.isRemoved()) {
			return -1;
		}
		return (int) Math.round(Math.sqrt(entity.distanceToSqr(mc.player)));
	}

	private static final class MinecraftView implements DepartureTracker.View {
		private final Minecraft mc;

		private MinecraftView(Minecraft mc) {
			this.mc = mc;
		}

		@Override
		public boolean listed(String uuid) {
			UUID id = parse(uuid);
			return id != null && TabList.listed(mc, id);
		}

		@Override
		public int distanceIfNearby(String uuid) {
			UUID id = parse(uuid);
			if (id == null || mc == null || mc.level == null || mc.player == null) {
				return -1;
			}
			Entity entity = mc.level.getPlayerByUUID(id);
			if (entity == null || entity.isRemoved()) {
				return -1;
			}
			return (int) Math.round(Math.sqrt(entity.distanceToSqr(mc.player)));
		}

		private static UUID parse(String value) {
			if (value == null) {
				return null;
			}
			try {
				return UUID.fromString(value);
			} catch (IllegalArgumentException ignored) {
				return null;
			}
		}
	}

	// ------------------------------------------------------------- lifecycle

	/** Clears every per-session clue and subject. Called on a world change. */
	public void reset() {
		resetDetection();
		resetSubjects();
		VanishPackets.clear();
	}

	private void resetDetection() {
		engine.clear();
		tracker.clear();
		ghost.clear();
		gameModes.clear();
		lastCritical = null;
		lastClusterDetection = null;
		lastClusterX = 0.0;
		lastClusterY = 0.0;
		lastClusterZ = 0.0;
		activeAlert = null;
		lastChatRows = Set.of();
		leaveAt.clear();
		probeClient.reset();
		ticks = 0L;
	}

	public void resetSubjects() {
		subjects.clear();
		seeded = false;
	}

	/** The strongest critical fusion produced by the last tick, or null. */
	public VanishEngine.Critical lastCritical() {
		return lastCritical;
	}

	/** Signal-fusion state, exposed for the alert layer. */
	public VanishEngine engine() {
		return engine;
	}

	/** Timed gate state, exposed for the alert layer and gate tests. */
	public GhostMemory ghost() {
		return ghost;
	}

	/** The last hidden-presence cluster, exposed as a detection for the alert layer. */
	public VanishEngine.Detection lastClusterDetection() {
		return lastClusterDetection;
	}

	/** True when this name has an active VANISH clue. Used by Staff List. */
	public boolean hasActiveVanish(String name, long now) {
		if (name == null) {
			return false;
		}
		for (VanishEngine.Signal signal : engine.signals(now)) {
			if (signal.type() == VanishEngine.Type.VANISH && signal.subject().equalsIgnoreCase(name)) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ HUD

	@Override
	public int anchor() {
		return clamp(anchor.get(), HudElementModule.ANCHOR_TOP_LEFT, HudElementModule.ANCHOR_BOTTOM_RIGHT);
	}

	@Override
	public int offsetX() {
		return offsetX.get();
	}

	@Override
	public int offsetY() {
		return offsetY.get();
	}

	@Override
	public void setAnchor(int cell) {
		anchor.set(clamp(cell, HudElementModule.ANCHOR_TOP_LEFT, HudElementModule.ANCHOR_BOTTOM_RIGHT));
	}

	@Override
	public int defaultAnchor() {
		return HudElementModule.ANCHOR_TOP_RIGHT;
	}

	@Override
	public void moveTo(int x, int y) {
		offsetX.set(clamp(x, -HudElementModule.MAX_NUDGE, HudElementModule.MAX_NUDGE));
		offsetY.set(clamp(y, -HudElementModule.MAX_NUDGE, HudElementModule.MAX_NUDGE));
	}

	@Override
	public void resetPosition() {
		moveTo(0, 0);
	}

	@Override
	public String panelLabel() {
		return "Anti Vanish";
	}

	@Override
	public int panelWidth(Minecraft mc) {
		return AntiVanishHudRenderer.listWidth(mc, visibleRows(System.currentTimeMillis(), HUD_ROWS));
	}

	@Override
	public int panelHeight(Minecraft mc) {
		return AntiVanishHudRenderer.listHeight(mc, visibleRows(System.currentTimeMillis(), HUD_ROWS));
	}

	@Override
	public void renderPanel(GuiGraphics graphics, int x, int y, int w, int h) {
		AntiVanishHudRenderer.renderList(graphics, x, y, w, h,
				visibleRows(System.currentTimeMillis(), HUD_ROWS));
	}

	/** Draws the alert in the ordinary HUD layer. */
	public void onHudRender(GuiGraphics graphics, int screenW, int screenH) {
		if (!isEnabled() || graphics == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.screen instanceof AbstractContainerScreen) {
			// The screen mixin paints the over-inventory copy after the screen.
			return;
		}
		drawAlert(graphics, screenW, screenH);
	}

	/** Draws only the alert above an open inventory screen. */
	public void onInventoryHudRender(GuiGraphics graphics, int screenW, int screenH) {
		if (!isEnabled() || graphics == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || !(mc.screen instanceof AbstractContainerScreen)) {
			return;
		}
		drawAlert(graphics, screenW, screenH);
	}

	private void drawAlert(GuiGraphics graphics, int screenW, int screenH) {
		if (activeAlert == null) {
			return;
		}
		double elapsed = (System.currentTimeMillis() - activeAlert.startedAt()) / 1000.0;
		double[] frame = AlertTimeline.frame(elapsed);
		if (frame == null) {
			activeAlert = null;
			return;
		}
		try {
			AntiVanishHudRenderer.renderAlert(graphics, screenW, screenH, activeAlert.name(),
					activeAlert.distance(), frame);
		} catch (Throwable failure) {
			Guard.report("anti-vanish alert draw", failure);
			try {
				AntiVanishHudRenderer.renderFallback(graphics, screenW, screenH, activeAlert.name(), frame[0]);
			} catch (Throwable fallbackFailure) {
				Guard.report("anti-vanish alert fallback", fallbackFailure);
			}
		}
	}

	private void playAlertSound() {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.getSoundManager() == null) {
			return;
		}
		try {
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ELDER_GUARDIAN_CURSE, 1.25f));
		} catch (RuntimeException failure) {
			Guard.report("anti-vanish alert sound", failure);
		}
	}

	private List<Row> visibleRows(long now, int limit) {
		List<Row> rows = new ArrayList<>(limit);
		int detectionLimit = Math.min(4, Math.max(0, limit));
		if (detectionLimit > 0) {
			for (VanishEngine.Detection detection : engine.detections(now, detectionLimit)) {
				rows.add(new Row(detection.name(), detection.reason()));
			}
		}
		boolean cluster = lastClusterDetection != null && lastClusterDetection.expiresAt() > now;
		if (cluster && limit > 0) {
			if (rows.size() >= limit) {
				rows.remove(rows.size() - 1);
			}
			rows.add(new Row("hidden presence", clusterDetail(now)));
		}
		return rows;
	}

	private String clusterDetail(long now) {
		if (lastClusterDetection == null || lastClusterDetection.expiresAt() <= now) {
			return "nearby";
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.player == null) {
			return "nearby";
		}
		double dx = lastClusterX - mc.player.getX();
		double dz = lastClusterZ - mc.player.getZ();
		int distance = (int) Math.round(Math.sqrt(mc.player.distanceToSqr(lastClusterX, lastClusterY, lastClusterZ)));
		return distance + " m " + compass(dx, dz);
	}

	private void updateChatLog(Minecraft mc, long now) {
		List<Row> rows = visibleRows(now, HUD_ROWS);
		Set<String> current = new HashSet<>(rows.size());
		for (Row row : rows) {
			current.add(chatKey(row));
		}
		if (chatLog.value() && mc != null && mc.gui != null) {
			for (Row row : rows) {
				String key = chatKey(row);
				if (!lastChatRows.contains(key)) {
					mc.gui.getChat().addMessage(Component.literal("Anti Vanish: " + row.name() + " — " + row.reason()));
				}
			}
		}
		lastChatRows = current;
	}

	/** The cluster row's distance changes every frame; its identity does not. */
	private static String chatKey(Row row) {
		if ("hidden presence".equalsIgnoreCase(row.name())) {
			return "hidden presence";
		}
		return row.name().toLowerCase(Locale.ROOT) + "\0" + row.reason();
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	public record Row(String name, String reason) {
	}

	private record ActiveAlert(String name, int distance, long startedAt) {
	}

	// --------------------------------------------------------------- subjects

	/**
	 * Records the listed state carried by a player-info entry.
	 *
	 * <p>The listed-since time starts only when an account enters the tab
	 * list. A rename or a latency update while it stays listed keeps the
	 * original time; an unlisted account that is listed again starts over.</p>
	 */
	public void observeListed(UUID uuid, String name, boolean listed, long now) {
		if (uuid == null) {
			return;
		}
		Subject previous = subjects.get(uuid);
		String resolved = name == null || name.isBlank()
				? previous == null ? null : previous.name()
				: name;
		if (resolved == null) {
			return;
		}
		if (listed) {
			long since = previous != null && previous.listed() ? previous.listedSinceMs() : now;
			subjects.put(uuid, new Subject(resolved, since, true));
		} else {
			long since = previous == null ? now : previous.listedSinceMs();
			subjects.put(uuid, new Subject(resolved, since, false));
		}
	}

	/** True when the account is watched, listed, and past the grace period. */
	public boolean credible(UUID uuid, String name, long now) {
		Subject subject = uuid == null ? null : subjects.get(uuid);
		if (subject == null || !subject.listed() || !VanishText.plausibleName(subject.name())) {
			return false;
		}
		if (name != null && !name.isBlank() && !subject.name().equalsIgnoreCase(name)) {
			return false;
		}
		return onWatchlist(subject.name()) && now - subject.listedSinceMs() >= LISTED_GRACE_MS;
	}

	/** The name last associated with an account, or null. */
	public String nameOf(UUID uuid) {
		Subject subject = uuid == null ? null : subjects.get(uuid);
		return subject == null ? null : subject.name();
	}

	/** Every {uuid, name} pair the tracker may use to parse a leave line. */
	public List<String[]> knownPairs() {
		List<String[]> pairs = new ArrayList<>(subjects.size());
		for (Map.Entry<UUID, Subject> entry : subjects.entrySet()) {
			Subject subject = entry.getValue();
			if (subject.name() != null && !subject.name().isBlank()) {
				pairs.add(new String[]{entry.getKey().toString(), subject.name()});
			}
		}
		return pairs;
	}

	/** Drops an account whose player-info entry the server removed. */
	public void forgetSubject(UUID uuid) {
		if (uuid != null) {
			subjects.remove(uuid);
			gameModes.remove(uuid);
		}
	}

	/**
	 * True when the name is on the watchlist.
	 *
	 * <p>A Bedrock account arrives with a leading dot, while the user types
	 * the Java name without one. Accepting both spellings keeps the same
	 * watchlist useful on a server that prefixes its Bedrock players.</p>
	 */
	public boolean onWatchlist(String name) {
		if (name == null || name.isBlank()) {
			return false;
		}
		if (watchlist.matches(name)) {
			return true;
		}
		return name.startsWith(".") && name.length() > 1 && watchlist.matches(name.substring(1));
	}

	private void seed(Minecraft mc) {
		ClientPacketListener connection = mc == null ? null : mc.getConnection();
		if (connection == null) {
			return;
		}
		long now = System.currentTimeMillis();
		Set<UUID> listedNow = new HashSet<>();
		for (PlayerInfo info : connection.getListedOnlinePlayers()) {
			UUID id = idOf(info);
			if (id != null) {
				listedNow.add(id);
			}
		}
		for (PlayerInfo info : connection.getOnlinePlayers()) {
			UUID id = idOf(info);
			String name = nameOf(info);
			if (id == null || name == null) {
				continue;
			}
			Subject previous = subjects.get(id);
			boolean listed = listedNow.contains(id);
			if (previous == null) {
				subjects.put(id, new Subject(name, now, listed));
			} else if (previous.listed() != listed || !name.equals(previous.name())) {
				observeListed(id, name, listed, now);
			}
		}
		seeded = true;
	}

	// ---------------------------------------------------------- world gates

	private SoundGate.Context soundContext(Minecraft mc, String id, double x, double y, double z, long now) {
		GhostMemory.Pos pos = GhostMemory.Pos.of(x, y, z);
		boolean near = nearMe(mc, x, y, z);
		boolean powered = false;
		if (isDoorOrTrapdoorSound(id)) {
			if (poweredAt(mc, pos)) {
				ghost.markPowered(pos, now);
			}
			powered = ghost.poweredMechanism(pos, now);
		}
		return new SoundGate.Context(near, visibleCause(mc, x, y, z),
				ghost.explosionRelated(x, y, z, now), powered, ghost.msSinceAction(now),
				ghost.selfPlaced(pos, now), isDoorOrGateSound(id) && villagerNear(mc, x, y, z),
				ghost.containerBusy(now), SoundGate.containerSound(id));
	}

	private boolean nearMe(Minecraft mc, double x, double y, double z) {
		return mc != null && mc.player != null && mc.player.distanceToSqr(x, y, z) <= 64.0 * 64.0;
	}

	/** Spatial query only: never walks the whole entity list. */
	private boolean visibleCause(Minecraft mc, double x, double y, double z) {
		if (mc == null || mc.level == null || mc.player == null) {
			return false;
		}
		AABB box = new AABB(x - 4.0, y - 4.0, z - 4.0, x + 4.0, y + 4.0, z + 4.0);
		return !mc.level.getEntities(mc.player, box, entity -> entity != mc.player
				&& entity.distanceToSqr(x, y, z) <= 16.0
				&& ((entity instanceof AbstractClientPlayer player && !player.isInvisible())
				|| entity instanceof Projectile)).isEmpty();
	}

	private boolean villagerNear(Minecraft mc, double x, double y, double z) {
		if (mc == null || mc.level == null) {
			return false;
		}
		AABB box = new AABB(x - 3.0, y - 3.0, z - 3.0, x + 3.0, y + 3.0, z + 3.0);
		return !mc.level.getEntitiesOfClass(Villager.class, box,
				villager -> villager.distanceToSqr(x, y, z) <= 9.0).isEmpty();
	}

	private boolean poweredAt(Minecraft mc, GhostMemory.Pos pos) {
		if (mc == null || mc.level == null) {
			return false;
		}
		BlockPos block = new BlockPos(pos.x(), pos.y(), pos.z());
		return mc.level.hasNeighborSignal(block)
				|| mc.level.hasNeighborSignal(block.above())
				|| mc.level.hasNeighborSignal(block.below());
	}

	private boolean ambientSource(Minecraft mc, double x, double y, double z) {
		if (mc == null || mc.level == null) {
			return false;
		}
		BlockPos center = BlockPos.containing(x, y, z);
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-2, -2, -2), center.offset(2, 2, 2))) {
			if (ambient(mc.level.getBlockState(pos))) {
				return true;
			}
		}
		return false;
	}

	private boolean withinTwoAndAHalfBlocks(Minecraft mc, double x, double y, double z) {
		return mc != null && mc.player != null && mc.player.distanceToSqr(x, y, z) <= 2.5 * 2.5;
	}

	private static boolean ambient(BlockState state) {
		var block = state.getBlock();
		return block instanceof CampfireBlock || block instanceof AbstractFurnaceBlock
				|| block instanceof TorchBlock || block instanceof WallTorchBlock
				|| block instanceof FireBlock || block instanceof CandleBlock
				|| block instanceof CandleCakeBlock || block instanceof RespawnAnchorBlock;
	}

	private static boolean isDoorOrTrapdoorSound(String id) {
		return SoundGate.path(id, "door.open", "door.close");
	}

	private static boolean isDoorOrGateSound(String id) {
		return SoundGate.path(id, "door.open", "door.close", "fence_gate.open", "fence_gate.close");
	}

	private static UUID idOf(PlayerInfo info) {
		return info == null || info.getProfile() == null ? null : info.getProfile().id();
	}

	private static String nameOf(PlayerInfo info) {
		return info == null || info.getProfile() == null ? null : info.getProfile().name();
	}

	// ------------------------------------------------------------ persistence

	@Override
	public void loadConfig(JsonObject json) {
		watchlist.set(ConfigValues.stringList(json, "watchlist"));
		chatLog.set(ConfigValues.boolValue(json, "chat_log", false));
		probe.set(ConfigValues.boolValue(json, "probe", false));
		anchor.set(ConfigValues.intValue(json, "anchor", HudElementModule.ANCHOR_TOP_RIGHT));
		offsetX.set(ConfigValues.intValue(json, "offset_x", 0));
		offsetY.set(ConfigValues.intValue(json, "offset_y", 0));
	}

	@Override
	public void saveConfig(JsonObject json) {
		JsonArray names = new JsonArray();
		for (String entry : watchlist.entries()) {
			names.add(entry);
		}
		json.add("watchlist", names);
		json.addProperty("chat_log", chatLog.value());
		json.addProperty("probe", probe.value());
		json.addProperty("anchor", anchor());
		json.addProperty("offset_x", offsetX());
		json.addProperty("offset_y", offsetY());
	}
}
