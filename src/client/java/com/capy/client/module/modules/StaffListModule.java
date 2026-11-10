package com.capy.client.module.modules;

import com.capy.client.config.ConfigValues;
import com.capy.client.gui.McCanvas;
import com.capy.client.gui.McType;
import com.capy.client.gui.Ui;
import com.capy.client.gui.UiCanvas;
import com.capy.client.gui.UiText;
import com.capy.client.gui.UiTheme;
import com.capy.client.gui.UiType;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.Category;
import com.capy.client.module.Configurable;
import com.capy.client.module.Module;
import com.capy.client.module.ModuleManager;
import com.capy.client.module.SliderSetting;
import com.capy.client.module.Tickable;
import com.capy.client.module.WatchlistSetting;
import com.capy.client.module.modules.antiadmin.AdminDetector;
import com.capy.client.module.modules.antiadmin.ProbeState;
import com.capy.client.module.modules.antiadmin.TabList;
import com.capy.client.module.modules.hud.HudElementModule;
import com.capy.client.module.modules.hud.HudPanel;
import com.capy.client.util.Guard;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Staff List: the watched accounts that are online right now.
 *
 * <p>Each row is a head and a name, nothing else. A name is online when it is
 * in the tab list, in the world, or proven online by Anti Admin's probe leak or
 * tab-hidden proof. A vanished account therefore stays visible while it is
 * proven online; an offline name never gets a row. Rows are removed only after
 * three seconds without any online source, so a tab-list rebuild cannot make a
 * row flicker.</p>
 */
public class StaffListModule extends Module implements Configurable, HudPanel, Tickable {
	private static final UiType TYPE = new McType();
	private static final int PAD = 8;
	private static final int ROW_H = 22;
	private static final int HEAD = 18;
	private static final int INNER = 8;
	private static final int HEADER_H = 15;
	private static final int RADIUS = 10;
	private static final int BAR_W = 2;
	private static final int MIN_W = 108;
	private static final long ABSENT_GRACE_MS = 3000L;
	private static final long FADE_MS = 150L;
	private static final long PROBE_INTERVAL_MS = 5000L;
	private static final int MAX_NUDGE = HudElementModule.MAX_NUDGE;

	private final WatchlistSetting watchlist = setting(new WatchlistSetting("watchlist"));
	private final BooleanSetting showHeader = setting(new BooleanSetting("show_header", false));
	private final BooleanSetting hideWhenEmpty = setting(new BooleanSetting("hide_when_empty", true));
	private final SliderSetting background = setting(new SliderSetting("background", 100, 0, 100));
	private final SliderSetting anchor = setting(new SliderSetting("anchor", HudElementModule.ANCHOR_TOP_LEFT,
			HudElementModule.ANCHOR_TOP_LEFT, HudElementModule.ANCHOR_BOTTOM_RIGHT));
	private final SliderSetting offsetX = setting(new SliderSetting("offset_x", 0, -MAX_NUDGE, MAX_NUDGE));
	private final SliderSetting offsetY = setting(new SliderSetting("offset_y", 0, -MAX_NUDGE, MAX_NUDGE));

	private record Row(String name, UUID uuid, PlayerSkin skin, float alpha) {
	}

	private final List<Row> rows = new ArrayList<>();
	private final Map<String, Long> lastOnline = new HashMap<>();
	private final Map<String, UUID> lastUuid = new HashMap<>();
	private final Map<String, PlayerSkin> lastSkin = new HashMap<>();
	private final Map<String, Float> fade = new HashMap<>();
	private boolean rowsBuilt;
	private List<String> lastWatchlist = List.of();
	private Object lastLevel;
	private long lastScanMs;
	private long lastRefreshMs;
	private long probedAt;
	private List<AdminDetector.Match> scannedWorld = List.of();
	private List<AdminDetector.TabTell> scannedTells = List.of();
	private List<AdminDetector.TabTell> scannedUnlisted = List.of();
	private List<AdminDetector.Match> scannedVanished = List.of();

	public StaffListModule() {
		super(Category.DONUT_SMP, "staff_list");
	}

	/** Drops per-world state on a world change or disconnect. */
	public void resetSession() {
		rows.clear();
		lastOnline.clear();
		lastUuid.clear();
		lastSkin.clear();
		fade.clear();
		scannedWorld = List.of();
		scannedTells = List.of();
		scannedUnlisted = List.of();
		scannedVanished = List.of();
		rowsBuilt = false;
		lastLevel = null;
		lastScanMs = 0L;
		lastRefreshMs = 0L;
		probedAt = 0L;
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
	public void moveTo(int x, int y) {
		offsetX.set(clamp(x, -MAX_NUDGE, MAX_NUDGE));
		offsetY.set(clamp(y, -MAX_NUDGE, MAX_NUDGE));
	}

	@Override
	public void resetPosition() {
		moveTo(0, 0);
	}

	// -------------------------------------------------------------------- size

	@Override
	public int panelWidth(Minecraft mc) {
		ensureRows(mc);
		if (rows.isEmpty() && hideWhenEmpty.value()) {
			return 0;
		}
		Font font = mc == null ? null : mc.font;
		UiText medium = TYPE.medium();
		int widest = 0;
		for (Row row : rows) {
			widest = Math.max(widest, font != null ? font.width(row.name()) : medium.width(row.name()));
		}
		int rowWidth = PAD * 2 + HEAD + INNER + widest;
		int header = showHeader.value() ? PAD * 2 + medium.width(title()) + UiTheme.SP_4 + 22 : 0;
		return Math.max(MIN_W, Math.max(rowWidth, header));
	}

	@Override
	public int panelHeight(Minecraft mc) {
		ensureRows(mc);
		if (rows.isEmpty() && hideWhenEmpty.value()) {
			return 0;
		}
		int height = PAD * 2 + rows.size() * ROW_H;
		if (showHeader.value()) {
			height += HEADER_H;
		}
		return height;
	}

	// ----------------------------------------------------------------- draw

	@Override
	public void renderPanel(GuiGraphics graphics, int x, int y, int w, int h) {
		if (w <= 0 || h <= 0 || rows.isEmpty()) {
			return;
		}
		UiCanvas c = McCanvas.of(graphics);
		UiText regular = TYPE.regular();
		UiText medium = TYPE.medium();
		int alpha = backgroundAlpha();

		if (alpha > 0) {
			Ui.roundRect(c, x + 1, y + 2, w, h, RADIUS, 0x33000000);
			Ui.surface(c, x, y, w, h, RADIUS,
					Ui.withAlpha(UiTheme.PANEL_ALT, alpha),
					Ui.withAlpha(UiTheme.PANEL_BOTTOM, Math.round(alpha * 0.94f)));
			Ui.surfaceEdge(c, x, y, w, h, RADIUS,
					Ui.withAlpha(UiTheme.BORDER, Math.round(alpha * 0.80f)),
					Ui.withAlpha(UiTheme.BORDER, Math.round(alpha * 0.28f)));
			Ui.sheen(c, x, y, w, h, RADIUS, (System.currentTimeMillis() % 9000L) / 9000f);
			Ui.surface(c, x + RADIUS, y, Math.max(0, w - RADIUS * 2), 1, 0,
					Ui.withAlpha(0xFFFFFF, Math.round(alpha * 0.10f)));
		}

		int rowTop = y + PAD;
		if (showHeader.value()) {
			int barH = 8;
			Ui.surface(c, x + PAD, rowTop + (HEADER_H - barH) / 2, BAR_W, barH, 1,
					UiTheme.ACCENT, UiTheme.ACCENT_DEEP);
			int textX = x + PAD + BAR_W + UiTheme.SP_3;
			medium.draw(c, title(), textX, rowTop + Math.max(0, (HEADER_H - medium.lineHeight()) / 2),
					UiTheme.TEXT_2);
			String count = String.valueOf(rows.size());
			int countW = regular.width(count);
			regular.draw(c, count, x + w - PAD - countW,
					rowTop + Math.max(0, (HEADER_H - regular.lineHeight()) / 2), UiTheme.TEXT_3);
			rowTop += HEADER_H;
		}

		for (int i = 0; i < rows.size(); i++) {
			drawRow(c, graphics, rows.get(i), x, rowTop + i * ROW_H, w);
		}
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!isEnabled()) {
			return;
		}
		AntiAdminModule antiAdmin = ModuleManager.get().get(AntiAdminModule.class);
		long now = System.currentTimeMillis();
		boolean dirty = antiAdmin != null && antiAdmin.isEnabled() && antiAdmin.isStaffListDirty();
		boolean changed = !watchlist.entries().equals(lastWatchlist);
		Object level = mc == null ? null : mc.level;
		boolean needScan = !rowsBuilt || dirty || changed || level != lastLevel
				|| now - lastScanMs >= 1000L;
		refreshRows(mc, now, needScan);
	}

	private void ensureRows(Minecraft mc) {
		if (!rowsBuilt) {
			refreshRows(mc, System.currentTimeMillis(), true);
		}
	}

	private void refreshRows(Minecraft mc, long now, boolean forceScan) {
		rowsBuilt = true;
		lastWatchlist = List.copyOf(watchlist.entries());
		lastLevel = mc == null ? null : mc.level;
		long elapsed = lastRefreshMs == 0L ? 0L : Math.max(0L, now - lastRefreshMs);
		lastRefreshMs = now;
		rows.clear();
		if (mc == null || mc.level == null) {
			return;
		}
		AntiAdminModule antiAdmin = ModuleManager.get().get(AntiAdminModule.class);
		boolean withProbe = antiAdmin != null && antiAdmin.isEnabled();
		if (forceScan) {
			scannedWorld = AdminDetector.scan(mc, watchlist);
			scannedTells = AdminDetector.scanTells(mc, watchlist);
			scannedUnlisted = AdminDetector.scanAllTabEntries(mc, watchlist);
			scannedVanished = AdminDetector.scanForVanished(mc, watchlist);
			lastScanMs = now;
		}

		Set<String> candidates = new HashSet<>();
		for (String name : watchlist.entries()) {
			candidates.add(name.toLowerCase(Locale.ROOT));
		}
		for (String name : new ArrayList<>(lastOnline.keySet())) {
			if (now - lastOnline.getOrDefault(name, 0L) <= ABSENT_GRACE_MS + FADE_MS) {
				candidates.add(name);
			}
		}

		for (String key : candidates) {
			String name = displayName(key);
			AdminDetector.Match entity = findMatch(scannedWorld, name);
			AdminDetector.TabTell tell = findTell(scannedTells, name);
			PlayerInfo info = tabEntry(mc, name);
			boolean tabVisible = info != null && info.getProfile() != null && listed(mc, info.getProfile().id());
			boolean tabHidden = findTell(scannedUnlisted, name) != null
					|| findMatch(scannedVanished, name) != null;
			ProbeState probe = withProbe && antiAdmin != null ? antiAdmin.probeState(name) : ProbeState.UNKNOWN;
			boolean leaked = probe == ProbeState.HIDDEN;
			boolean online = StaffListFilter.isOnline(tabVisible, entity != null, tabHidden, leaked);

			if (online) {
				lastOnline.put(key, now);
				if (entity != null) {
					lastUuid.put(key, entity.uuid());
					if (entity.skin() != null) {
						lastSkin.put(key, entity.skin());
					}
				} else if (info != null && info.getProfile() != null) {
					lastUuid.put(key, info.getProfile().id());
					if (info.getSkin() != null) {
						lastSkin.put(key, info.getSkin());
					}
				} else if (tell != null) {
					lastUuid.put(key, tell.match().uuid());
					if (tell.match().skin() != null) {
						lastSkin.put(key, tell.match().skin());
					}
				}
			}

			// A watchlist name that is offline and has never been seen online
			// must not create a row. The old default alpha of 1.0 made one
			// appear at full opacity every tick and fade back to nothing.
			boolean seen = lastOnline.containsKey(key) || fade.containsKey(key);
			if (!StaffListFilter.shouldConsiderCandidate(online, seen)) {
				continue;
			}

			long absentFor = now - lastOnline.getOrDefault(key, 0L);
			if (lastOnline.get(key) != null
					&& !StaffListFilter.keepAfterAbsence(online, absentFor)
					&& absentFor > ABSENT_GRACE_MS + FADE_MS) {
				fade.remove(key);
				lastOnline.remove(key);
				lastUuid.remove(key);
				lastSkin.remove(key);
				continue;
			}

			float target = absentFor <= ABSENT_GRACE_MS ? 1f : 0f;
			float current = fade.getOrDefault(key, online ? 0f : 1f);
			current = StaffListFilter.nextAlpha(current, target, elapsed, FADE_MS);
			fade.put(key, current);
			if (current <= 0.01f && absentFor > ABSENT_GRACE_MS) {
				lastOnline.remove(key);
				lastUuid.remove(key);
				lastSkin.remove(key);
				fade.remove(key);
				continue;
			}

			UUID uuid = lastUuid.get(key);
			PlayerSkin skin = online ? currentSkin(entity, tell, info, lastSkin.get(key)) : lastSkin.get(key);
			StaffListFilter.HeadChoice head = StaffListFilter.headChoice(skin != null, uuid != null);
			if (head == StaffListFilter.HeadChoice.DEFAULT_SKIN) {
				skin = DefaultPlayerSkin.get(uuid);
			}
			rows.add(new Row(name, uuid, skin, current));
		}

		rows.sort(Comparator.comparing(Row::name, String.CASE_INSENSITIVE_ORDER)
				.thenComparing(Row::name));
		probeMissing(antiAdmin, withProbe, mc, now);
	}

	private static PlayerSkin currentSkin(AdminDetector.Match entity, AdminDetector.TabTell tell,
			PlayerInfo info, PlayerSkin previous) {
		if (entity != null && entity.skin() != null) {
			return entity.skin();
		}
		if (tell != null && tell.match().skin() != null) {
			return tell.match().skin();
		}
		if (info != null && info.getSkin() != null) {
			return info.getSkin();
		}
		return previous;
	}

	private String displayName(String key) {
		for (String entry : watchlist.entries()) {
			if (entry.equalsIgnoreCase(key)) {
				return entry;
			}
		}
		for (Row row : rows) {
			if (row.name().equalsIgnoreCase(key)) {
				return row.name();
			}
		}
		return key;
	}

	private void probeMissing(AntiAdminModule antiAdmin, boolean withProbe, Minecraft mc, long now) {
		if (!withProbe || antiAdmin == null || !antiAdmin.probingAllowed() || mc == null || mc.level == null) {
			return;
		}
		if (probedAt != 0L && now - probedAt < PROBE_INTERVAL_MS) {
			return;
		}
		probedAt = now;
		Set<String> inWorld = new HashSet<>();
		try {
			for (AbstractClientPlayer player : mc.level.players()) {
				if (player == null || player.getGameProfile() == null) {
					continue;
				}
				String name = player.getGameProfile().name();
				if (name != null && !name.isEmpty()) {
					inWorld.add(name.toLowerCase(Locale.ROOT));
				}
			}
		} catch (Throwable failure) {
			Guard.report("staff-list-probe-world", failure);
			return;
		}
		for (String name : watchlist.entries()) {
			if (!inWorld.contains(name.toLowerCase(Locale.ROOT))) {
				antiAdmin.enqueueProbe(name);
			}
		}
	}

	private static AdminDetector.Match findMatch(List<AdminDetector.Match> matches, String name) {
		for (AdminDetector.Match match : matches) {
			if (equalsName(match.identity(), name) || equalsName(match.username(), name)) {
				return match;
			}
		}
		return null;
	}

	private static AdminDetector.TabTell findTell(List<AdminDetector.TabTell> tells, String name) {
		for (AdminDetector.TabTell tell : tells) {
			if (equalsName(tell.match().identity(), name) || equalsName(tell.match().username(), name)) {
				return tell;
			}
		}
		return null;
	}

	private static boolean equalsName(String a, String b) {
		return a != null && b != null && a.equalsIgnoreCase(b);
	}

	private static boolean listed(Minecraft mc, UUID uuid) {
		if (uuid == null) {
			return false;
		}
		try {
			return TabList.listed(mc, uuid);
		} catch (Throwable failure) {
			Guard.report("staff-list-listed", failure);
			return false;
		}
	}

	private static PlayerInfo tabEntry(Minecraft mc, String name) {
		if (mc == null || mc.getConnection() == null || name == null) {
			return null;
		}
		try {
			for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
				if (info == null || info.getProfile() == null) {
					continue;
				}
				if (equalsName(info.getProfile().name(), name)) {
					return info;
				}
				net.minecraft.network.chat.Component display = info.getTabListDisplayName();
				if (display != null && equalsName(display.getString().trim(), name)) {
					return info;
				}
			}
		} catch (RuntimeException | LinkageError failure) {
			// Connection closing: an online row stays until its grace expires.
			Guard.report("staff-list tab entry", failure);
		}
		return null;
	}

	private void drawRow(UiCanvas c, GuiGraphics graphics, Row row, int x, int y, int w) {
		UiText medium = TYPE.medium();
		Minecraft mc = Minecraft.getInstance();
		Font font = mc == null ? null : mc.font;
		int ink = Ui.withAlpha(UiTheme.TEXT, Math.round(255 * row.alpha()));

		int headX = x + PAD;
		int headY = y + (ROW_H - HEAD) / 2;
		PlayerSkin skin = row.skin();
		boolean drawn = false;
		if (skin != null && graphics != null && row.alpha() > 0.01f) {
			try {
				PlayerFaceRenderer.draw(graphics, skin, headX, headY, HEAD,
						Ui.withAlpha(0xFFFFFF, Math.round(255 * row.alpha())));
				drawn = true;
			} catch (RuntimeException failure) {
				// A skin that cannot be drawn costs the face, not the row.
				Guard.report("staff-list skin", failure);
			}
		}
		if (!drawn && graphics != null && row.alpha() > 0.01f) {
			Ui.surface(c, headX, headY, HEAD, HEAD, 4,
					Ui.withAlpha(0xFFFFFFFF, Math.round(0x2E * row.alpha())),
					Ui.withAlpha(UiTheme.ACCENT_DEEP, Math.round(0x66 * row.alpha())));
		}

		int textX = headX + HEAD + INNER;
		if (font != null && graphics != null) {
			graphics.drawString(font, row.name(), textX, y + (ROW_H - font.lineHeight) / 2, ink, false);
		} else {
			medium.draw(c, row.name(), textX, Ui.centerTextY(y, ROW_H, medium), ink);
		}
	}

	@Override
	public String panelLabel() {
		return "Staff list";
	}

	private String title() {
		return "STAFF LIST";
	}

	private int backgroundAlpha() {
		return Math.round(Math.max(0f, Math.min(1f, (float) background.real() / 100f)) * 255f);
	}

	// ------------------------------------------------------------ persistence

	@Override
	public void loadConfig(JsonObject json) {
		watchlist.set(ConfigValues.stringList(json, "watchlist"));
		showHeader.set(ConfigValues.boolValue(json, "show_header", false));
		hideWhenEmpty.set(ConfigValues.boolValue(json, "hide_when_empty", true));
		background.set(ConfigValues.intValue(json, "background", 100));
		anchor.set(ConfigValues.intValue(json, "anchor", HudElementModule.ANCHOR_TOP_LEFT));
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
		json.addProperty("show_header", showHeader.value());
		json.addProperty("hide_when_empty", hideWhenEmpty.value());
		json.addProperty("background", Math.round(background.real()));
		json.addProperty("anchor", anchor());
		json.addProperty("offset_x", offsetX());
		json.addProperty("offset_y", offsetY());
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
