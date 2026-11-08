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
import com.capy.client.module.modules.antiadmin.ChatPresence;
import com.capy.client.module.modules.antiadmin.PresencePolicy;
import com.capy.client.module.modules.antiadmin.PresenceStatus;
import com.capy.client.module.modules.antiadmin.ProbeState;
import com.capy.client.module.modules.antiadmin.TabList;
import com.capy.client.module.modules.hud.HudElementModule;
import com.capy.client.module.modules.hud.HudPanel;
import com.capy.client.util.Guard;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.level.GameType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Staff List: a box of the accounts you watch, drawn as a HUD panel.
 *
 * <p>It is the display half of a watchlist. The names are typed once, in the
 * module's settings, and from then on the box shows a Minecraft head and the
 * name for each of them - on screen, in the corner you put it, for as long as
 * the module is on. It does not detect anything and it asks the server nothing:
 * the head is the skin the tab list already carries, so a name the server has
 * never mentioned simply renders without a face instead of costing a lookup.</p>
 *
 * <h2>Why it is a panel and not a chip</h2>
 * A HUD element is one line of text. This is a stack of rows with icons, and its
 * height depends on how many people are on the list, so it draws itself. What
 * it borrows from {@link HudElementModule} is the placement rules - the nine
 * cell anchor plus a pixel nudge - through {@link HudPanel}, which is what lets
 * the HUD editor drag it around exactly like a chip.</p>
 *
 * <h2>The look</h2>
 * Liquid glass: a translucent plate in the theme's own surface colours, a
 * brighter hairline for the rim, and - in the glass theme - the same specular
 * band the chips carry, so the box belongs to the interface rather than sitting
 * on top of it. The heads are drawn with vanilla's player face renderer, which
 * is the only way to get the skin's UVs and its hat layer right.</p>
 */
public class StaffListModule extends Module implements Configurable, HudPanel, Tickable {
	/** Resolved once: a font lookup per frame is not free. */
	private static final UiType TYPE = new McType();

	private static final int PAD = 8;
	private static final int ROW_H = 22;
	private static final int HEAD = 18;
	/** Gap between the head and the name, and between the bar and the head. */
	private static final int INNER = 8;
	private static final int HEADER_H = 15;
	private static final int RADIUS = 10;
	private static final int BAR_W = 2;
	private static final int MIN_W = 108;
	/** How far the nudge may go, matching the chips. */
	private static final int MAX_NUDGE = HudElementModule.MAX_NUDGE;
	private static final long SHEEN_PERIOD_MS = 9000L;
	/**
	 * How often the list may ask the probe about names it cannot see.
	 *
	 * <p>Deliberately slow. The probe already has its own jittered gap between
	 * requests; this is the second, coarser gate that keeps a panel drawn every
	 * frame from turning into a queue of requests, and it means a name typed
	 * into the list at most enters the probe queue once every few seconds.</p>
	 */
	private static final long PROBE_INTERVAL_MS = 5000L;

	private final WatchlistSetting watchlist = setting(new WatchlistSetting("watchlist"));
	private final BooleanSetting showHeader = setting(new BooleanSetting("show_header", true));
	private final SliderSetting background = setting(new SliderSetting("background", 92, 0, 100));
	private final SliderSetting anchor = setting(new SliderSetting("anchor", HudElementModule.ANCHOR_TOP_RIGHT,
			HudElementModule.ANCHOR_TOP_LEFT, HudElementModule.ANCHOR_BOTTOM_RIGHT));
	private final SliderSetting offsetX = setting(new SliderSetting("offset_x", 0, -MAX_NUDGE, MAX_NUDGE));
	private final SliderSetting offsetY = setting(new SliderSetting("offset_y", 0, -MAX_NUDGE, MAX_NUDGE));
	/**
	 * Whether the Anti Admin evidence may label a row.
	 *
	 * <p>On by default: the whole point of watching a name is to be told when
	 * the server is hiding them. It is a setting because the evidence comes from
	 * another module, and a user who turned that module off to stop its alerts
	 * should not find its tags on the staff list.</p>
	 */
	private final BooleanSetting evidence = setting(new BooleanSetting("evidence", true));

	/** One row of the box, resolved before it is measured or drawn. */
	private record Row(String name, PlayerSkin skin, PresenceStatus status, String evidence,
			long evidenceAgeMs, long lastSeenAt) {
	}

	/**
	 * Rows for the current frame, rebuilt once per tick.
	 *
	 * <p>The list is resolved once and then measured and drawn from the same
	 * values. Resolving inside {@code panelWidth}, {@code panelHeight} and the
	 * draw would scan the world three times a frame and could, on a frame where
	 * a player leaves between the calls, measure a different set of rows from
	 * the one it painted. The expensive world and tab scans are cached for a
	 * second; the cheap per-name rebuild runs every tick.</p>
	 */
	private final List<Row> rows = new ArrayList<>();
	private final Map<String, Long> lastSeen = new HashMap<>();
	private boolean rowsBuilt;
	private List<String> lastWatchlist = List.of();
	private Object lastLevel;
	private long lastScanMs;
	private List<AdminDetector.Match> scannedWorld = List.of();
	private List<AdminDetector.TabTell> scannedTells = List.of();
	private List<AdminDetector.TabTell> scannedUnlisted = List.of();
	private List<AdminDetector.Match> scannedVanished = List.of();
	/** Last time the probe was handed the names the world does not contain. */
	private long probedAt;
	/**
	 * The last skin seen for a name, for the session.
	 *
	 * <p>This is what keeps a head in the box after a vanish. A vanish usually
	 * removes both the entity and the player list entry, and those are the only
	 * two places the client can read a skin from - so without a memory here the
	 * face of the one person the list exists to watch was the first thing to go
	 * the moment they used {@code /vanish}.</p>
	 */
	private final Map<String, PlayerSkin> rememberedSkins = new HashMap<>();

	public StaffListModule() {
		super(Category.DONUT_SMP, "staff_list");
	}

	/** Drops per-world row and last-seen state on a world change or disconnect. */
	public void resetSession() {
		rows.clear();
		lastSeen.clear();
		scannedWorld = List.of();
		scannedTells = List.of();
		scannedUnlisted = List.of();
		scannedVanished = List.of();
		rowsBuilt = false;
		lastLevel = null;
		lastScanMs = 0L;
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

	/**
	 * Width of the box: the wider of the header and the longest row.
	 *
	 * <p>Measured through the same fonts the drawing uses, so the plate is
	 * exactly as wide as the text inside it instead of leaving a band of empty
	 * glass beside a short name.</p>
	 */
	@Override
	public int panelWidth(Minecraft mc) {
		ensureRows(mc);
		UiText medium = TYPE.medium();
		UiText regular = TYPE.regular();
		// Names are drawn in Minecraft's own font, so they are measured with
		// it too; the interface face is only the fallback for a frame before
		// the game's font exists.
		Font font = mc == null ? null : mc.font;
		int widest = 0;
		for (Row row : rows) {
			int width = font != null ? font.width(row.name()) : medium.width(row.name());
			String tag = tagFor(row);
			if (!tag.isEmpty()) {
				int tagW = font != null ? font.width(tag) : regular.width(tag);
				width += UiTheme.SP_3 + tagW;
			}
			widest = Math.max(widest, width);
		}
		int row = PAD * 2 + HEAD + INNER + widest;
		int header = showHeader.value()
				? PAD * 2 + medium.width(title()) + UiTheme.SP_4 + 22
				: 0;
		return Math.max(MIN_W, Math.max(row, header));
	}

	/** Height of the box: one row per watched name, plus padding and header. */
	@Override
	public int panelHeight(Minecraft mc) {
		ensureRows(mc);
		int height = PAD * 2 + Math.max(1, rows.size()) * ROW_H;
		if (showHeader.value()) {
			height += HEADER_H;
		}
		return height;
	}

	// ----------------------------------------------------------------- draw

	@Override
	public void renderPanel(GuiGraphics graphics, int x, int y, int w, int h) {
		if (w <= 0 || h <= 0) {
			return;
		}
		UiCanvas c = McCanvas.of(graphics);
		UiText regular = TYPE.regular();
		UiText medium = TYPE.medium();
		int alpha = backgroundAlpha();

		if (alpha > 0) {
			// The same four steps every surface in the interface is built from,
			// in the same order: shadow, plate, hairline, and the glass sheen.
			Ui.roundRect(c, x + 1, y + 2, w, h, RADIUS, 0x33000000);
			Ui.surface(c, x, y, w, h, RADIUS,
					Ui.withAlpha(UiTheme.PANEL_ALT, alpha),
					Ui.withAlpha(UiTheme.PANEL_BOTTOM, Math.round(alpha * 0.94f)));
			Ui.surfaceEdge(c, x, y, w, h, RADIUS,
					Ui.withAlpha(UiTheme.BORDER, Math.round(alpha * 0.80f)),
					Ui.withAlpha(UiTheme.BORDER, Math.round(alpha * 0.28f)));
		}

		if (UiTheme.isGlass()) {
			float sheen = (System.currentTimeMillis() % SHEEN_PERIOD_MS) / (float) SHEEN_PERIOD_MS;
			Ui.sheen(c, x, y, w, h, RADIUS, sheen);
			Ui.surface(c, x + RADIUS, y, Math.max(0, w - RADIUS * 2), 1, 0,
					Ui.withAlpha(0xFFFFFF, Math.round(alpha * 0.10f)));
		}

		int rowTop = y + PAD;
		if (showHeader.value()) {
			// An accent tick, the name, and the count: the header says what the
			// box is without spending a whole row on it.
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

		int count = rows.size();
		if (count == 0) {
			// An empty box is still a box: the panel has to be visible in the
			// editor before a name is typed, and a heading with no rows reads as
			// a broken module rather than as an empty list.
			regular.draw(c, "No players yet", x + PAD + BAR_W + INNER, rowTop + (ROW_H - regular.lineHeight()) / 2,
					UiTheme.TEXT_3);
			return;
		}
		for (int i = 0; i < count; i++) {
			int rowY = rowTop + i * ROW_H;
			drawRow(c, graphics, rows.get(i), x, rowY, w, i < count - 1);
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
		// Rows are rebuilt every tick; the world and tab collections are only
		// re-read when something changed or a second has passed. That keeps the
		// visible tags fresh without walking every entity and entry per tick.
		refreshRows(mc, now, needScan);
	}

	/** The first frame can arrive before the first tick; build once then. */
	private void ensureRows(Minecraft mc) {
		if (!rowsBuilt) {
			refreshRows(mc, System.currentTimeMillis(), true);
		}
	}

	/**
	 * Resolves every watched name to what is known about it right now.
	 *
	 * <p>Called once per tick, or immediately on a watchlist/level change, and
	 * never from the drawing path.</p>
	 */
	private void refreshRows(Minecraft mc, long now, boolean forceScan) {
		rowsBuilt = true;
		lastWatchlist = List.copyOf(watchlist.entries());
		lastLevel = mc == null ? null : mc.level;
		rows.clear();
		if (mc == null || mc.level == null) {
			return;
		}
		AntiAdminModule antiAdmin = ModuleManager.get().get(AntiAdminModule.class);
		boolean withEvidence = evidence.value() && antiAdmin != null && antiAdmin.isEnabled();
		if (forceScan) {
			scannedWorld = AdminDetector.scan(mc, watchlist);
			scannedTells = AdminDetector.scanTells(mc, watchlist);
			scannedUnlisted = AdminDetector.scanAllTabEntries(mc, watchlist);
			scannedVanished = AdminDetector.scanForVanished(mc, watchlist);
			lastScanMs = now;
		}
		List<AdminDetector.Match> inWorld = scannedWorld;
		List<AdminDetector.TabTell> tells = scannedTells;
		List<AdminDetector.TabTell> unlisted = scannedUnlisted;
		List<AdminDetector.Match> vanished = scannedVanished;
		for (String name : watchlist.entries()) {
			String key = name.toLowerCase(Locale.ROOT);
			AdminDetector.Match entity = findMatch(inWorld, name);
			AdminDetector.TabTell tell = findTell(tells, name);
			PlayerInfo info = tabEntry(mc, name);
			boolean listed = info != null && info.getProfile() != null && listed(mc, info.getProfile().id());
			boolean tabHidden = findTell(unlisted, name) != null || findMatch(vanished, name) != null;
			boolean spectator = (tell != null && tell.tell() == AdminDetector.Tell.SPECTATOR)
					|| (info != null && info.getGameMode() == GameType.SPECTATOR);
			boolean inWorldNow = entity != null;
			double distance = entity == null ? -1.0 : entity.distance();
			boolean withinRange = distance >= 0.0 && antiAdmin != null
					&& distance <= antiAdmin.detectionRange();
			ProbeState probe = withEvidence ? antiAdmin.probeState(name) : ProbeState.UNKNOWN;
			// A probe hit is disproved by the name appearing in the tab list;
			// an absent result is likewise no longer meaningful once listed.
			boolean probeHidden = probe == ProbeState.HIDDEN && !listed;
			boolean probeAbsent = probe == ProbeState.ABSENT && !listed;
			PresencePolicy.Decision decision = PresencePolicy.decide(new PresencePolicy.Evidence(
					probeHidden, tabHidden, spectator,
					inWorldNow && !listed, inWorldNow, listed, withinRange,
					probeAbsent, ChatPresence.recent(name, 180_000L)));
			boolean seen = inWorldNow || info != null || tell != null || probeHidden;
			long lastSeenAt = lastSeen.getOrDefault(key, 0L);
			if (seen) {
				lastSeenAt = now;
				lastSeen.put(key, lastSeenAt);
			}
			long evidenceAge = now - lastSeenAt;
			if (probeHidden || probeAbsent) {
				long probeAge = antiAdmin.probeAgeMs(name);
				if (probeAge >= 0L) {
					evidenceAge = probeAge;
				}
			} else if (seen) {
				evidenceAge = 0L;
			}
			PlayerSkin skin = entity != null ? entity.skin()
					: tell != null ? tell.match().skin()
					: info != null ? info.getSkin() : null;
			if (skin != null) {
				rememberedSkins.put(key, skin);
			} else {
				skin = rememberedSkins.get(key);
			}
			rows.add(new Row(name, skin, decision.status(), decision.evidence(), evidenceAge, lastSeenAt));
		}
		rows.sort(Comparator.comparingInt((Row row) -> row.status().rowOrder())
				.thenComparing(Row::name, String.CASE_INSENSITIVE_ORDER));
		probeMissing(antiAdmin, withEvidence, mc, now);
	}

	/**
	 * Hands the probe every watched name the world does not contain.
	 *
	 * <p>Anti Admin now does this for its own watchlist, so this is only a
	 * supplement for a Staff List that watches different names. It is called
	 * from the tick rebuild, never from the drawing path, and the probe applies
	 * its own per-name gap and request cap.</p>
	 */
	private void probeMissing(AntiAdminModule antiAdmin, boolean withEvidence, Minecraft mc, long now) {
		if (!withEvidence || antiAdmin == null || !antiAdmin.probingAllowed() || mc == null || mc.level == null) {
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

	/**
	 * True when the tab list is actually drawing the account.
	 *
	 * <p>This used to ask whether the connection held an entry, which is true for
	 * a hidden account too: the entry is exactly what a listed-flag vanish
	 * leaves behind. The distinction only exists because vanilla keeps the two
	 * collections apart, and reading the wrong one turned a vanished account
	 * into a plain row.</p>
	 */
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

	/** The player list entry for a name, matched case-insensitively. */
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
		} catch (RuntimeException | LinkageError ignored) {
			// A connection in the middle of closing; the remembered skin stands.
		}
		return null;
	}

	/** The tag drawn at the end of a row: status, evidence, and its age. */
	private static String tagFor(Row row) {
		String evidence = row.evidence();
		long age = Math.max(0L, row.evidenceAgeMs());
		if (evidence == null || evidence.isBlank() || "none".equals(evidence)) {
			if (row.lastSeenAt() > 0L) {
				evidence = "last seen";
				age = Math.max(0L, System.currentTimeMillis() - row.lastSeenAt());
			} else {
				return row.status().name();
			}
		}
		return row.status().name() + ", " + evidence + ", " + formatAge(age);
	}

	private static String formatAge(long ms) {
		if (ms < 1000L) {
			return "now";
		}
		long seconds = ms / 1000L;
		if (seconds < 60L) {
			return seconds + "s";
		}
		long minutes = seconds / 60L;
		if (minutes < 60L) {
			return minutes + "m";
		}
		return (minutes / 60L) + "h";
	}

	/** The colour of a status: amber for a vanish, accent for the rest. */
	private static int tagColor(PresenceStatus status) {
		return switch (status) {
			case VANISHED -> 0xFFFFC24B;
			case NEARBY -> UiTheme.ACCENT;
			case ONLINE -> UiTheme.TEXT_2;
			case SPECTATOR -> UiTheme.ACCENT_DEEP;
			case OFFLINE, UNKNOWN -> UiTheme.TEXT_3;
		};
	}

	/** One watched account: head, name, and any status tag. */
	private void drawRow(UiCanvas c, GuiGraphics graphics, Row row, int x, int y, int w,
			boolean separator) {
		UiText medium = TYPE.medium();
		UiText regular = TYPE.regular();
		Minecraft mc = Minecraft.getInstance();
		Font font = mc == null ? null : mc.font;

		int headX = x + PAD;
		int headY = y + (ROW_H - HEAD) / 2;
		PlayerSkin skin = row.skin();
		boolean drawn = false;
		if (skin != null && graphics != null) {
			try {
				// Vanilla's own face renderer: the skin's UVs, its hat layer and
				// its fallback are all handled exactly as the tab list does.
				PlayerFaceRenderer.draw(graphics, skin, headX, headY, HEAD, 0xFFFFFFFF);
				drawn = true;
			} catch (RuntimeException ignored) {
				// A skin that cannot be drawn costs the face, not the row.
			}
		}
		if (!drawn) {
			// No skin this frame: a plate in the player's colour with their
			// initial, which still identifies the row at a glance.
			Ui.surface(c, headX, headY, HEAD, HEAD, 4,
					Ui.withAlpha(0xFFFFFFFF, 0x2E), Ui.withAlpha(UiTheme.ACCENT_DEEP, 0x66));
			Ui.outline(c, headX, headY, HEAD, HEAD, 4, Ui.withAlpha(UiTheme.BORDER, 0xB0));
			String first = row.name().isEmpty() ? "?" : row.name().substring(0, 1).toUpperCase(Locale.ROOT);
			Ui.drawCentered(c, medium, first, headX + HEAD / 2,
					Ui.centerTextY(headY, HEAD, medium), UiTheme.TEXT);
		}

		int textX = headX + HEAD + INNER;
		int maxW = Math.max(0, x + w - PAD - textX);
		String tag = tagFor(row);
		int tagW = font != null ? font.width(tag) : regular.width(tag);
		if (font != null && graphics != null) {
			// Minecraft's own face, at its own scale and without the interface
			// shadow: this line is a player name, and it should look like the
			// name the tab list would show rather than like UI copy.
			graphics.drawString(font, row.name(), textX, y + (ROW_H - font.lineHeight) / 2,
					UiTheme.TEXT, false);
			if (!tag.isEmpty()) {
				graphics.drawString(font, tag, x + w - PAD - tagW,
						y + (ROW_H - font.lineHeight) / 2, tagColor(row.status()), false);
			}
		} else {
			medium.draw(c, row.name(), textX, Ui.centerTextY(y, ROW_H, medium), UiTheme.TEXT);
			if (!tag.isEmpty()) {
				regular.draw(c, tag, x + w - PAD - tagW,
						Ui.centerTextY(y, ROW_H, regular), tagColor(row.status()));
			}
		}

		if (separator) {
			Ui.fadeLine(c, textX, y + ROW_H - 1, maxW,
					Ui.withAlpha(UiTheme.BORDER, 0x40));
		}
	}

	@Override
	public String panelLabel() {
		return "Staff list";
	}

	private String title() {
		return "STAFF LIST";
	}

	/** Plate opacity, 0..255. */
	private int backgroundAlpha() {
		return Math.round(Math.max(0f, Math.min(1f, (float) background.real() / 100f)) * 255f);
	}

	// ------------------------------------------------------------ persistence

	@Override
	public void loadConfig(JsonObject json) {
		watchlist.set(ConfigValues.stringList(json, "watchlist"));
		showHeader.set(ConfigValues.boolValue(json, "show_header", true));
		background.set(ConfigValues.intValue(json, "background", 92));
		evidence.set(ConfigValues.boolValue(json, "evidence", true));
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
		json.addProperty("show_header", showHeader.value());
		json.addProperty("background", Math.round(background.real()));
		json.addProperty("evidence", evidence.value());
		json.addProperty("anchor", anchor());
		json.addProperty("offset_x", offsetX());
		json.addProperty("offset_y", offsetY());
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
