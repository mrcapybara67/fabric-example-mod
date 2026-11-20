package com.capy.client.module.modules.esp;

import com.capy.client.config.ConfigValues;
import com.capy.client.finder.core.ChunkStyles;
import com.capy.client.finder.core.Chunks;
import com.capy.client.finder.core.DrawSink;
import com.capy.client.finder.core.FinderBeams;
import com.capy.client.finder.core.FinderRules;
import com.capy.client.finder.core.FlagLook;
import com.capy.client.gui.McCanvas;
import com.capy.client.gui.McType;
import com.capy.client.gui.Ui;
import com.capy.client.gui.UiCanvas;
import com.capy.client.gui.UiText;
import com.capy.client.gui.UiTheme;
import com.capy.client.gui.UiType;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.ColorSetting;
import com.capy.client.module.IntSetting;
import com.capy.client.module.modules.antiadmin.TabList;
import com.capy.client.module.modules.hud.HudElementModule;
import com.capy.client.module.modules.hud.HudPanel;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * Activity Inspector: a compact list of the other players in view and what
 * they appear to be doing, plus markers on chunks with underground activity.
 *
 * <p>Every row and every marker answers a setting: Players can hide the whole
 * list, Ignore NPCs drops accounts that are not in the tab list, Remember keeps
 * a last-seen position for a while, Boxes and Tracers decide how that position
 * is marked, and the underground half uses Below Y, Min Hits, Window, Own
 * Radius and Render Y instead of one fixed depth.</p>
 */
public final class ActivityInspectorModule extends FinderModule implements HudPanel {
	private static final UiType TYPE = new McType();
	private static final int PAD = 8;
	private static final int HEADER_H = 15;
	private static final int ROW_H = 16;
	private static final int RADIUS = 10;
	private static final int BAR_W = 2;
	private static final int DOT = 3;
	private static final int MIN_W = 190;
	private static final int MAX_W = 460;
	private static final int MAX_ROWS = 6;
	private static final int MAX_NUDGE = HudElementModule.MAX_NUDGE;
	private static final double VIEW_RANGE = 64.0;
	private static final long ROW_REFRESH_MS = 100L;
	private static final long LOW_REFRESH_MS = 1000L;

	private final BooleanSetting players = describe(setting(new BooleanSetting("players", true)),
			"Show other players in the list and their boxes.");
	private final BooleanSetting ignoreNpcs = describe(setting(new BooleanSetting("ignore_npcs", true)),
			"Skip accounts that are not in the tab list.");
	private final IntSetting remember = describe(setting(new IntSetting("remember", 5, 1, 30, 1)),
			"Minutes a last-seen position is kept after the player disappears.");
	private final BooleanSetting boxes = describe(setting(new BooleanSetting("boxes", true)),
			"Draw a box around every tracked player.");
	private final BooleanSetting trails = describe(setting(new BooleanSetting("trails", true)),
			"Draw a trail behind moving players.");
	private final IntSetting trailLength = describe(setting(new IntSetting("trail_length", 30, 5, 120, 1),
			() -> trails.value()), "Seconds of movement kept in a trail.");
	private final BooleanSetting lastSeenBeams = describe(setting(new BooleanSetting("last_seen_beams", true)),
			"Draw a beam where a remembered player was last seen.");
	private final BooleanSetting notifyPlayers = describe(setting(new BooleanSetting("notify_players", false)),
			"Show a toast when a new player comes into view.");
	private final ColorSetting playerColor = describe(setting(new ColorSetting("player_color", 0xFF5AC8FF)),
			"The colour of player rows, boxes and trails.");
	private final ColorSetting approachingColor = describe(setting(new ColorSetting("approaching_color", 0xFFFF5050)),
			"The colour used when a player is moving towards you.");
	private final BooleanSetting underground = describe(setting(new BooleanSetting("underground", true)),
			"Track activity below Below Y.");
	private final IntSetting belowY = describe(setting(new IntSetting("below_y", 0, -64, 64, 1),
			() -> underground.value()), "The Y level under which activity is underground.");
	private final IntSetting minHits = describe(setting(new IntSetting("min_hits", 8, 2, 100, 1)),
			"Underground sightings a chunk needs before it is marked.");
	private final IntSetting window = describe(setting(new IntSetting("window", 5, 1, 30, 1)),
			"Minutes the underground hit count is measured over.");
	private final IntSetting ownRadius = describe(setting(new IntSetting("own_radius", 2, 0, 6, 1)),
			"Chunks around you treated as your own area.");
	private final BooleanSetting ignoreOwnAreas = describe(setting(new BooleanSetting("ignore_own_areas", true)),
			"Do not mark activity in the chunks around you.");
	private final BooleanSetting notifyUnderground = describe(setting(new BooleanSetting("notify_underground", false)),
			"Show a toast when a new underground chunk is marked.");
	private final BooleanSetting chunkSquares = describe(setting(new BooleanSetting("chunk_squares", true)),
			"Draw a chunk square at Render Y for each marked chunk.");
	private final IntSetting renderY = describe(setting(new IntSetting("render_y", 63, -64, 320, 1)),
			"The Y level the chunk squares are drawn at.");
	private final BooleanSetting beams = describe(setting(new BooleanSetting("beams", true)),
			"Draw vertical beams above the busiest underground chunks.");
	private final IntSetting beamCount = describe(setting(new IntSetting("beam_count", 3, 1, 10, 1),
			() -> beams.value()), "How many underground beams are drawn at once.");
	private final ColorSetting lowColor = describe(setting(new ColorSetting("low_color", 0xFF468CFF)),
			"The colour for a chunk at the hit threshold.");
	private final ColorSetting highColor = describe(setting(new ColorSetting("high_color", 0xFFFF463C)),
			"The colour for a chunk far above the hit threshold.");
	private final BooleanSetting tracer = describe(setting(new BooleanSetting("tracer", true)),
			"Draw a fading line from the crosshair to every tracked player.");

	private final IntSetting anchor = describe(setting(new IntSetting("anchor", HudElementModule.ANCHOR_TOP_LEFT,
			HudElementModule.ANCHOR_TOP_LEFT, HudElementModule.ANCHOR_BOTTOM_RIGHT, 1)),
			"Which corner of the HUD the panel is placed in.");
	private final IntSetting offsetX = describe(setting(new IntSetting("offset_x", 0, -MAX_NUDGE, MAX_NUDGE, 1)),
			"Horizontal nudge from the anchor.");
	private final IntSetting offsetY = describe(setting(new IntSetting("offset_y", 0, -MAX_NUDGE, MAX_NUDGE, 1)),
			"Vertical nudge from the anchor.");

	private final Map<Integer, Trace> traces = new HashMap<>();
	private final Map<UUID, Deque<TrailPoint>> trailMap = new HashMap<>();
	private final Map<UUID, LastSeen> lastSeen = new HashMap<>();
	private final Map<UUID, double[]> positions = new HashMap<>();
	private final Map<Long, Deque<Long>> undergroundHits = new HashMap<>();
	private final Map<Long, Long> lowSince = new HashMap<>();
	private final Map<AABB, Integer> boxColors = new IdentityHashMap<>();
	private List<Row> rows = List.of();
	private List<AABB> playerBoxes = List.of();
	private Set<Long> lowChunks = Set.of();
	private Set<Long> previousLow = Set.of();
	private Set<UUID> visiblePlayers = Set.of();
	private long lastRowsAt;
	private long lastLowAt;
	private Object lastLevel;
	private boolean rowsFresh;
	private boolean lowFresh;

	public ActivityInspectorModule() {
		super("activity_inspector", 0xFF8FB8FF);
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!beginTick(mc)) {
			return;
		}
		refreshRows(mc);
		refreshLow(mc);
	}

	/** The inspector has no block scan of its own; it reads players and packets. */
	@Override
	public boolean scan(net.minecraft.client.multiplayer.ClientLevel level,
			net.minecraft.world.level.chunk.LevelChunk chunk, long deadlineNanos) {
		return true;
	}

	@Override
	public List<AABB> boxes() {
		return playerBoxes;
	}

	@Override
	public boolean fills() {
		return false;
	}

	@Override
	public boolean outlines() {
		return boxes.value();
	}

	@Override
	public boolean tracers() {
		return tracer.value();
	}

	@Override
	public boolean tracerFade() {
		return tracer.value();
	}

	@Override
	public int colorFor(AABB box, double timeSec) {
		Integer colour = boxColors.get(box);
		return colour == null ? playerColor.argbAt(timeSec) : colour;
	}

	@Override
	public void drawFlags(DrawSink sink, double timeSec, double timeMs) {
		if (players.value() && trails.value()) {
			for (Deque<TrailPoint> trail : trailMap.values()) {
				TrailPoint previous = null;
				for (TrailPoint point : trail) {
					if (previous != null) {
						sink.line(previous.x(), previous.y() + 0.1, previous.z(),
								point.x(), point.y() + 0.1, point.z(), 2.0f,
								(150 << 24) | (playerColor.argbAt(timeSec) & 0xFFFFFF));
					}
					previous = point;
				}
			}
		}
		if (players.value() && lastSeenBeams.value()) {
			for (Map.Entry<UUID, LastSeen> entry : lastSeen.entrySet()) {
				if (visiblePlayers.contains(entry.getKey())) {
					continue;
				}
				LastSeen seen = entry.getValue();
				emitBeam(sink, seen.x(), seen.z(), seen.y() + 0.05, seen.y() + 50.0, 0.3,
						(110 << 24) | (playerColor.argbAt(timeSec) & 0xFFFFFF));
			}
		}
		if (!chunkSquares.value() || lowChunks.isEmpty()) {
			return;
		}
		int low = lowColor.argbAt(timeSec);
		int high = highColor.argbAt(timeSec);
		List<Long> ordered = new ArrayList<>(lowChunks);
		ordered.sort(Comparator.comparingDouble(this::flagDistance));
		int beamLimit = beams.value() ? Math.min(beamCount.get(), ordered.size()) : 0;
		List<FlagLook.Flag> look = new ArrayList<>(ordered.size());
		for (int i = 0; i < ordered.size(); i++) {
			long key = ordered.get(i);
			int hits = hits(key);
			double strength = Math.min(1.0, hits / (double) Math.max(1, minHits.get()));
			int argb = FinderRules.ramp(low, high, strength);
			look.add(FlagLook.Flag.chunk(key, terrainOrFlat(key), argb & 0xFFFFFF,
					flagDistance(key), FlagLook.easeOutCubic(lowSince.getOrDefault(key, 0L), timeMs),
					tracer.value(), i < beamLimit, 60.0));
		}
		FlagLook.drawAll(sink, look, timeMs, quality(), autoSimplified(), tracer.value(), tracerOrigin());
	}

	private int hits(long key) {
		Deque<Long> times = undergroundHits.get(key);
		return times == null ? 0 : times.size();
	}

	private void refreshRows(Minecraft mc) {
		long now = System.currentTimeMillis();
		if (rowsFresh && mc != null && mc.level == lastLevel && now - lastRowsAt < ROW_REFRESH_MS) {
			return;
		}
		rowsFresh = true;
		lastRowsAt = now;
		lastLevel = mc == null ? null : mc.level;
		if (mc == null || mc.player == null || mc.level == null || !players.value()) {
			rows = List.of();
			playerBoxes = List.of();
			boxColors.clear();
			positions.clear();
			trailMap.clear();
			visiblePlayers = Set.of();
			return;
		}
		Frustum frustum = mc.levelRenderer.getCapturedFrustum();
		Set<UUID> listed = ignoreNpcs.value() ? TabList.listed(mc) : null;
		Set<UUID> seen = new HashSet<>();
		Set<UUID> visible = new HashSet<>();
		List<Row> found = new ArrayList<>();
		List<AABB> nextBoxes = new ArrayList<>();
		Map<AABB, Integer> nextColors = new IdentityHashMap<>();
		Map<UUID, double[]> nextPositions = new HashMap<>();
		boolean announced = false;
		for (AbstractClientPlayer player : mc.level.players()) {
			if (player == mc.player || player.isSpectator()) {
				continue;
			}
			UUID uuid = player.getUUID();
			if (listed != null && !listed.contains(uuid)) {
				continue;
			}
			double distanceSq = player.distanceToSqr(mc.player);
			if (distanceSq > VIEW_RANGE * VIEW_RANGE) {
				continue;
			}
			AABB bounds = player.getBoundingBox().inflate(1.0);
			if (frustum != null && !frustum.isVisible(bounds)) {
				continue;
			}
			Trace trace = traces.computeIfAbsent(player.getId(), ignored -> new Trace());
			double dx = player.getX() - trace.x;
			double dz = player.getZ() - trace.z;
			if (!trace.primed) {
				trace.primed = true;
				dx = 0;
				dz = 0;
				trace.lastDistance = distanceSq;
			}
			trace.speed = trace.speed * 0.65 + Math.hypot(dx, dz) * 20.0 * 0.35;
			trace.x = player.getX();
			trace.z = player.getZ();
			double distance = Math.sqrt(distanceSq);
			boolean approaching = FinderRules.approaching(Math.sqrt(trace.lastDistance), distance, trace.speed);
			trace.lastDistance = distanceSq;

			ItemStack held = player.getMainHandItem();
			String item = held.isEmpty() ? "empty" : held.getHoverName().getString();
			boolean mining = player.swinging && isTool(held);
			boolean building = player.swinging && held.getItem() instanceof BlockItem;
			boolean moving = trace.speed >= 0.4 || player.walkAnimation.speed() > 0.04;
			String action = mining ? "mining" : building ? "building" : moving ? "moving" : "idle";
			found.add(new Row(player.getScoreboardName(), action, item, trace.speed, distance, approaching, uuid));
			AABB box = player.getBoundingBox().inflate(0.12);
			nextBoxes.add(box);
			nextColors.put(box, approaching ? approachingColor.argbAt(now / 1000.0)
					: playerColor.argbAt(now / 1000.0));
			nextPositions.put(uuid, new double[]{player.getX(), player.getY(), player.getZ()});
			seen.add(uuid);
			visible.add(uuid);
			lastSeen.put(uuid, new LastSeen(player.getScoreboardName(), player.getX(),
					player.getY(), player.getZ(), now));
			if (trails.value()) {
				Deque<TrailPoint> trail = trailMap.computeIfAbsent(uuid, ignored -> new ArrayDeque<>());
				trail.addLast(new TrailPoint(player.getX(), player.getY(), player.getZ(), now));
				long keep = Math.max(1, trailLength.get()) * 1000L;
				while (!trail.isEmpty() && now - trail.peekFirst().time() > keep) {
					trail.pollFirst();
				}
				while (trail.size() > 400) {
					trail.pollFirst();
				}
			}
			if (!visiblePlayers.contains(uuid) && notifyPlayers.value() && !announced) {
				announced = true;
				notify.toast(mc, "Activity Inspector", player.getScoreboardName() + " is in view");
			}
		}
		// The trace map is keyed by entity id, not UUID; prune by the ids seen.
		Set<Integer> ids = new HashSet<>();
		for (AbstractClientPlayer player : mc.level.players()) {
			if (seen.contains(player.getUUID())) {
				ids.add(player.getId());
			}
		}
		traces.keySet().retainAll(ids);
		long rememberMs = Math.max(1, remember.get()) * 60_000L;
		lastSeen.entrySet().removeIf(entry -> now - entry.getValue().time() > rememberMs);
		trailMap.keySet().retainAll(seen);
		positions.clear();
		positions.putAll(nextPositions);
		visiblePlayers = visible;
		found.sort(Comparator.comparingDouble(Row::distance).thenComparing(Row::name));
		rows = found.size() > MAX_ROWS ? List.copyOf(found.subList(0, MAX_ROWS)) : List.copyOf(found);
		playerBoxes = List.copyOf(nextBoxes);
		boxColors.clear();
		boxColors.putAll(nextColors);
	}

	private void refreshLow(Minecraft mc) {
		long now = System.currentTimeMillis();
		if (lowFresh && now - lastLowAt < LOW_REFRESH_MS) {
			return;
		}
		lowFresh = true;
		lastLowAt = now;
		if (!underground.value() || mc == null || mc.player == null) {
			lowChunks = Set.of();
			undergroundHits.clear();
			lowSince.clear();
			return;
		}
		int threshold = belowY.get();
		for (Map.Entry<UUID, double[]> entry : positions.entrySet()) {
			double[] position = entry.getValue();
			if (position[1] > threshold) {
				continue;
			}
			long key = Chunks.key((int) Math.floor(position[0]) >> 4, (int) Math.floor(position[2]) >> 4);
			Deque<Long> times = undergroundHits.computeIfAbsent(key, ignored -> new ArrayDeque<>());
			times.addLast(now);
			long windowMs = Math.max(1, window.get()) * 60_000L;
			while (!times.isEmpty() && now - times.peekFirst() > windowMs) {
				times.pollFirst();
			}
		}
		Set<Long> next = new HashSet<>();
		int playerChunkX = mc.player.blockPosition().getX() >> 4;
		int playerChunkZ = mc.player.blockPosition().getZ() >> 4;
		undergroundHits.entrySet().removeIf(entry -> {
			long key = entry.getKey();
			Deque<Long> times = entry.getValue();
			long windowMs = Math.max(1, window.get()) * 60_000L;
			while (!times.isEmpty() && now - times.peekFirst() > windowMs) {
				times.pollFirst();
			}
			if (times.isEmpty()) {
				return true;
			}
			if (times.size() < minHits.get()) {
				return false;
			}
			if (ignoreOwnAreas.value() && ownRadius.get() > 0
					&& Math.abs(Chunks.x(key) - playerChunkX) <= ownRadius.get()
					&& Math.abs(Chunks.z(key) - playerChunkZ) <= ownRadius.get()) {
				return false;
			}
			next.add(key);
			return false;
		});
		for (long key : next) {
			lowSince.putIfAbsent(key, now);
		}
		lowSince.keySet().retainAll(next);
		lowChunks = next;
		refreshTerrain(next);
		if (notifyUnderground.value()) {
			for (long key : next) {
				if (!previousLow.contains(key)) {
					notify.toast(mc, "Activity Inspector", "Underground activity near "
							+ (Chunks.x(key) * 16 + 8) + ", " + (Chunks.z(key) * 16 + 8));
					break;
				}
			}
		}
		previousLow = next;
	}

	@Override
	protected void onToggle(boolean state) {
		super.onToggle(state);
		if (!state) {
			rows = List.of();
			playerBoxes = List.of();
			boxColors.clear();
			lowChunks = Set.of();
			lowSince.clear();
			trailMap.clear();
			lastSeen.clear();
			positions.clear();
			undergroundHits.clear();
			rowsFresh = false;
			lowFresh = false;
		}
	}

	@Override
	protected void onChunkUnloaded(long key) {
		lowChunks = new HashSet<>(lowChunks);
		lowChunks.remove(key);
		undergroundHits.remove(key);
	}

	@Override
	public void resetSession() {
		super.resetSession();
		rows = List.of();
		playerBoxes = List.of();
		boxColors.clear();
		lowChunks = Set.of();
		previousLow = Set.of();
		visiblePlayers = Set.of();
		traces.clear();
		trailMap.clear();
		lastSeen.clear();
		positions.clear();
		undergroundHits.clear();
		rowsFresh = false;
		lowFresh = false;
		lastLevel = null;
		lastRowsAt = 0L;
		lastLowAt = 0L;
	}

	private static boolean isTool(ItemStack stack) {
		return stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.AXES)
				|| stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.HOES);
	}

	private void ensureRows(Minecraft mc) {
		if (mc != null) {
			refreshRows(mc);
		}
	}

	// --------------------------------------------------------------- placement

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
		return HudElementModule.ANCHOR_TOP_LEFT;
	}

	@Override
	public void moveTo(int x, int y) {
		offsetX.set(clamp(x, -MAX_NUDGE, MAX_NUDGE));
		offsetY.set(clamp(y, -MAX_NUDGE, MAX_NUDGE));
	}

	@Override
	public void resetPosition() {
		moveTo(0, 0);
	}

	@Override
	public int panelWidth(Minecraft mc) {
		ensureRows(mc);
		if (rows.isEmpty()) {
			return 0;
		}
		UiText regular = TYPE.regular();
		UiText medium = TYPE.medium();
		int widest = PAD * 2 + DOT * 2 + 6;
		for (Row row : rows) {
			widest = Math.max(widest, PAD * 2 + DOT * 2 + 6
					+ medium.width(row.name()) + 7 + regular.width(rest(row)));
		}
		int header = PAD * 2 + BAR_W + 6 + medium.width("ACTIVITY") + 24;
		return Math.max(MIN_W, Math.min(MAX_W, Math.max(widest, header)));
	}

	@Override
	public int panelHeight(Minecraft mc) {
		ensureRows(mc);
		return rows.isEmpty() ? 0 : PAD * 2 + HEADER_H + rows.size() * ROW_H;
	}

	@Override
	public void renderPanel(GuiGraphics graphics, int x, int y, int w, int h) {
		double seconds = System.nanoTime() / 1.0e9;
		ensureRows(Minecraft.getInstance());
		if (w <= 0 || h <= 0 || rows.isEmpty()) {
			return;
		}
		UiCanvas c = McCanvas.of(graphics);
		UiText regular = TYPE.regular();
		UiText medium = TYPE.medium();

		Ui.roundRect(c, x + 1, y + 2, w, h, RADIUS, 0x33000000);
		Ui.surface(c, x, y, w, h, RADIUS, UiTheme.PANEL_ALT, UiTheme.PANEL_BOTTOM);
		Ui.surfaceEdge(c, x, y, w, h, RADIUS, Ui.withAlpha(UiTheme.BORDER, 0xC0),
				Ui.withAlpha(UiTheme.BORDER, 0x44));
		Ui.sheen(c, x, y, w, h, RADIUS, (System.currentTimeMillis() % 9000L) / 9000f);
		Ui.surface(c, x + RADIUS, y, Math.max(0, w - RADIUS * 2), 1, 0,
				Ui.withAlpha(0xFFFFFF, 0x18));

		int headerY = y + PAD;
		Ui.surface(c, x + PAD, headerY + (HEADER_H - 8) / 2, BAR_W, 8, 1,
				UiTheme.ACCENT, UiTheme.ACCENT_DEEP);
		medium.draw(c, "ACTIVITY", x + PAD + BAR_W + 6,
				headerY + Math.max(0, (HEADER_H - medium.lineHeight()) / 2), UiTheme.TEXT_2);
		String count = Integer.toString(rows.size());
		regular.draw(c, count, x + w - PAD - regular.width(count),
				headerY + Math.max(0, (HEADER_H - regular.lineHeight()) / 2), UiTheme.TEXT_3);

		int rowTop = headerY + HEADER_H;
		for (int i = 0; i < rows.size(); i++) {
			Row row = rows.get(i);
			int rowY = rowTop + i * ROW_H;
			int dotX = x + PAD + DOT;
			int dotY = rowY + ROW_H / 2 - 1;
			int rowColor = row.approaching() ? approachingColor.argbAt(seconds) : playerColor.argbAt(seconds);
			Ui.circle(c, dotX, dotY, DOT, rowColor);

			int textX = x + PAD + DOT * 2 + 6;
			int nameRoom = x + w - PAD - textX;
			int nameWidth = medium.width(row.name());
			if (nameWidth > nameRoom) {
				Ui.drawEllipsized(c, medium, row.name(), textX,
						rowY + (ROW_H - medium.lineHeight()) / 2, nameRoom, rowColor);
				continue;
			}
			medium.draw(c, row.name(), textX, rowY + (ROW_H - medium.lineHeight()) / 2, rowColor);
			int restX = textX + nameWidth + 7;
			int restRoom = x + w - PAD - restX;
			if (restRoom > 0) {
				Ui.drawEllipsized(c, regular, rest(row), restX,
						rowY + (ROW_H - regular.lineHeight()) / 2, restRoom,
						row.approaching() ? approachingColor.argbAt(seconds) : UiTheme.TEXT_3);
			}
		}
	}

	@Override
	public String panelLabel() {
		return "Activity Inspector";
	}

	private static String rest(Row row) {
		return String.format(Locale.ROOT, "%s  %s  %.1f b/s", row.action(), row.held(), row.speed());
	}

	@Override
	public void loadConfig(JsonObject json) {
		super.loadConfig(json);
		anchor.set(ConfigValues.intValue(json, "anchor", HudElementModule.ANCHOR_TOP_LEFT));
		offsetX.set(ConfigValues.intValue(json, "offset_x", 0));
		offsetY.set(ConfigValues.intValue(json, "offset_y", 0));
		rowsFresh = false;
		lowFresh = false;
	}

	@Override
	public void saveConfig(JsonObject json) {
		super.saveConfig(json);
		json.addProperty("anchor", anchor());
		json.addProperty("offset_x", offsetX());
		json.addProperty("offset_y", offsetY());
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private record Row(String name, String action, String held, double speed, double distance,
			boolean approaching, UUID uuid) {
	}

	private record TrailPoint(double x, double y, double z, long time) {
	}

	private record LastSeen(String name, double x, double y, double z, long time) {
	}

	private static final class Trace {
		double x;
		double z;
		double speed;
		double lastDistance;
		boolean primed;
	}
}
