package com.capy.client.module.modules.esp;

import com.capy.client.config.ConfigValues;
import com.capy.client.finder.core.ChunkStyles;
import com.capy.client.finder.core.Chunks;
import com.capy.client.finder.core.DrawSink;
import com.capy.client.finder.core.FlagStyles;
import com.capy.client.finder.core.MotionScores;
import com.capy.client.gui.McCanvas;
import com.capy.client.gui.McType;
import com.capy.client.gui.Ui;
import com.capy.client.gui.UiCanvas;
import com.capy.client.gui.UiText;
import com.capy.client.gui.UiTheme;
import com.capy.client.gui.UiType;
import com.capy.client.module.ColorSetting;
import com.capy.client.module.SliderSetting;
import com.capy.client.module.modules.hud.HudElementModule;
import com.capy.client.module.modules.hud.HudPanel;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

/**
 * Activity Inspector: a compact list of the other players in view and what
 * they appear to be doing, plus markers on chunks with very frequent below-Y-0
 * block activity.
 *
 * <p>The rules are deliberately inferential, because the client is never told
 * what another player is doing:
 * <ul>
 *   <li><b>mining</b> - a swing animation while holding a pickaxe, axe, shovel
 *       or hoe;</li>
 *   <li><b>building</b> - a swing animation while holding a block item;</li>
 *   <li><b>moving</b> - a smoothed horizontal speed of at least 0.4 b/s, or a
 *       walk animation that is advancing;</li>
 *   <li><b>idle</b> - none of the above.</li>
 * </ul>
 * The held item is always shown, and the speed is the position change over the
 * last refresh, so the row says why the label was chosen. A row is considered
 * active for colouring when it is mining, building or moving. The panel is
 * hidden while nobody else is visible.</p>
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

	private final ColorSetting highActivityColor = setting(new ColorSetting("high_activity_color", 0xFFFF6B6B));
	private final SliderSetting anchor = setting(new SliderSetting("anchor", HudElementModule.ANCHOR_TOP_LEFT,
			HudElementModule.ANCHOR_TOP_LEFT, HudElementModule.ANCHOR_BOTTOM_RIGHT));
	private final SliderSetting offsetX = setting(new SliderSetting("offset_x", 0, -MAX_NUDGE, MAX_NUDGE));
	private final SliderSetting offsetY = setting(new SliderSetting("offset_y", 0, -MAX_NUDGE, MAX_NUDGE));

	private final Map<Integer, Trace> traces = new HashMap<>();
	private List<Row> rows = List.of();
	private Set<Long> lowChunks = Set.of();
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
		refreshLow();
	}

	/** The inspector has no block scan of its own; it reads players and the feed. */
	@Override
	public boolean scan(ClientLevel level, LevelChunk chunk, long deadlineNanos) {
		return true;
	}

	@Override
	public void drawFlags(DrawSink sink, double timeSec, double timeMs) {
		if (lowChunks.isEmpty() || level == null) {
			return;
		}
		double y = Math.max(level.getMinY(), 0) + 0.05;
		int rgb = highActivityColor.argbAt(timeSec) & 0xFFFFFF;
		List<Long> ordered = new ArrayList<>(lowChunks);
		ordered.sort(Comparator.comparingDouble(this::flagDistance));
		for (long key : ordered) {
			double distance = flagDistance(key);
			if (!drawableFlag(distance)) {
				continue;
			}
			if (fullStyle(distance, 90)) {
				double score = MotionFeed.lowScores().score(key, (long) timeMs);
				FlagStyles.heatRing(sink, Chunks.x(key) * 16.0 + 8, y, Chunks.z(key) * 16.0 + 8,
						6.0, rgb, score, timeMs);
			} else {
				ChunkStyles.simple(sink, Chunks.x(key), Chunks.z(key), y, rgb, false);
			}
		}
	}

	@Override
	public String statusLine() {
		return "players " + rows.size() + ", low activity " + lowChunks.size();
	}

	@Override
	protected void onToggle(boolean state) {
		super.onToggle(state);
		if (state) {
			MotionFeed.acquire();
		} else {
			MotionFeed.release();
			rows = List.of();
			lowChunks = Set.of();
			traces.clear();
			rowsFresh = false;
			lowFresh = false;
		}
	}

	@Override
	protected void onChunkUnloaded(long key) {
		MotionFeed.forget(key);
		if (lowChunks.contains(key)) {
			Set<Long> next = new HashSet<>(lowChunks);
			next.remove(key);
			lowChunks = next;
		}
	}

	@Override
	public void resetSession() {
		super.resetSession();
		rows = List.of();
		lowChunks = Set.of();
		traces.clear();
		rowsFresh = false;
		lowFresh = false;
		lastLevel = null;
		lastRowsAt = 0L;
		lastLowAt = 0L;
	}

	private void refreshRows(Minecraft mc) {
		long now = System.currentTimeMillis();
		if (rowsFresh && mc != null && mc.level == lastLevel && now - lastRowsAt < ROW_REFRESH_MS) {
			return;
		}
		rowsFresh = true;
		lastRowsAt = now;
		lastLevel = mc == null ? null : mc.level;
		if (mc == null || mc.player == null || mc.level == null) {
			rows = List.of();
			traces.clear();
			return;
		}
		Frustum frustum = mc.levelRenderer.getCapturedFrustum();
		Set<Integer> seen = new HashSet<>();
		List<Row> found = new ArrayList<>();
		for (AbstractClientPlayer player : mc.level.players()) {
			if (player == mc.player || player.isSpectator()) {
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
			}
			trace.speed = trace.speed * 0.65 + Math.hypot(dx, dz) * 20.0 * 0.35;
			trace.x = player.getX();
			trace.z = player.getZ();

			ItemStack held = player.getMainHandItem();
			String item = held.isEmpty() ? "empty" : held.getHoverName().getString();
			boolean mining = player.swinging && isTool(held);
			boolean building = player.swinging && held.getItem() instanceof BlockItem;
			boolean moving = trace.speed >= 0.4 || player.walkAnimation.speed() > 0.04;
			String action = mining ? "mining" : building ? "building" : moving ? "moving" : "idle";
			found.add(new Row(player.getScoreboardName(), action, item, trace.speed,
					Math.sqrt(distanceSq), mining || building || moving));
			seen.add(player.getId());
		}
		traces.keySet().retainAll(seen);
		found.sort(Comparator.comparingDouble(Row::distance).thenComparing(Row::name));
		rows = found.size() > MAX_ROWS ? List.copyOf(found.subList(0, MAX_ROWS)) : List.copyOf(found);
	}

	private void refreshLow() {
		long now = System.currentTimeMillis();
		if (lowFresh && now - lastLowAt < LOW_REFRESH_MS) {
			return;
		}
		lowFresh = true;
		lastLowAt = now;
		Set<Long> next = new HashSet<>();
		for (Map.Entry<Long, MotionScores.Level> entry : MotionFeed.lowScores().busy(now, 5.0).entrySet()) {
			if (entry.getValue() == MotionScores.Level.VERY_BUSY) {
				next.add(entry.getKey());
			}
		}
		lowChunks = next;
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
			Ui.circle(c, dotX, dotY, DOT, colorArgb(seconds));

			int textX = x + PAD + DOT * 2 + 6;
			int nameRoom = x + w - PAD - textX;
			int nameWidth = medium.width(row.name());
			if (nameWidth > nameRoom) {
				Ui.drawEllipsized(c, medium, row.name(), textX,
						rowY + (ROW_H - medium.lineHeight()) / 2, nameRoom, colorArgb(seconds));
				continue;
			}
			medium.draw(c, row.name(), textX, rowY + (ROW_H - medium.lineHeight()) / 2,
					colorArgb(seconds));
			int restX = textX + nameWidth + 7;
			int restRoom = x + w - PAD - restX;
			if (restRoom > 0) {
				Ui.drawEllipsized(c, regular, rest(row), restX,
						rowY + (ROW_H - regular.lineHeight()) / 2, restRoom,
						row.active() ? highActivityColor.argbAt(seconds) : UiTheme.TEXT_3);
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
		highActivityColor.load(json, "high_activity_color");
		anchor.set(ConfigValues.intValue(json, "anchor", HudElementModule.ANCHOR_TOP_LEFT));
		offsetX.set(ConfigValues.intValue(json, "offset_x", 0));
		offsetY.set(ConfigValues.intValue(json, "offset_y", 0));
		rowsFresh = false;
		lowFresh = false;
	}

	@Override
	public void saveConfig(JsonObject json) {
		super.saveConfig(json);
		highActivityColor.save(json, "high_activity_color");
		json.addProperty("anchor", anchor());
		json.addProperty("offset_x", offsetX());
		json.addProperty("offset_y", offsetY());
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private record Row(String name, String action, String held, double speed, double distance,
			boolean active) {
	}

	private static final class Trace {
		double x;
		double z;
		double speed;
		boolean primed;
	}
}
