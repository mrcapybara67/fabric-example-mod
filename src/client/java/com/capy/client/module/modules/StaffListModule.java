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
import com.capy.client.module.SliderSetting;
import com.capy.client.module.WatchlistSetting;
import com.capy.client.module.modules.hud.HudElementModule;
import com.capy.client.module.modules.hud.HudPanel;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.PlayerSkin;

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
public class StaffListModule extends Module implements Configurable, HudPanel {
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

	private final WatchlistSetting watchlist = setting(new WatchlistSetting("watchlist"));
	private final BooleanSetting showHeader = setting(new BooleanSetting("show_header", true));
	private final SliderSetting background = setting(new SliderSetting("background", 92, 0, 100));
	private final SliderSetting anchor = setting(new SliderSetting("anchor", HudElementModule.ANCHOR_TOP_RIGHT,
			HudElementModule.ANCHOR_TOP_LEFT, HudElementModule.ANCHOR_BOTTOM_RIGHT));
	private final SliderSetting offsetX = setting(new SliderSetting("offset_x", 0, -MAX_NUDGE, MAX_NUDGE));
	private final SliderSetting offsetY = setting(new SliderSetting("offset_y", 0, -MAX_NUDGE, MAX_NUDGE));

	public StaffListModule() {
		super(Category.DONUT_SMP, "staff_list");
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
		UiText medium = TYPE.medium();
		int widest = 0;
		for (String name : watchlist.entries()) {
			widest = Math.max(widest, medium.width(name));
		}
		int row = PAD * 2 + BAR_W + INNER + HEAD + INNER + widest;
		int header = showHeader.value()
				? PAD * 2 + medium.width(title()) + UiTheme.SP_4 + 22
				: 0;
		return Math.max(MIN_W, Math.max(row, header));
	}

	/** Height of the box: one row per watched name, plus padding and header. */
	@Override
	public int panelHeight(Minecraft mc) {
		int rows = Math.max(1, watchlist.entries().size());
		int height = PAD * 2 + rows * ROW_H;
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
			String count = String.valueOf(watchlist.entries().size());
			int countW = regular.width(count);
			regular.draw(c, count, x + w - PAD - countW,
					rowTop + Math.max(0, (HEADER_H - regular.lineHeight()) / 2), UiTheme.TEXT_3);
			rowTop += HEADER_H;
		}

		Minecraft mc = Minecraft.getInstance();
		int count = watchlist.entries().size();
		if (count == 0) {
			// An empty box is still a box: the panel has to be visible in the
			// editor before a name is typed, and a heading with no rows reads as
			// a broken module rather than as an empty list.
			regular.draw(c, "No players yet", x + PAD + BAR_W + INNER, rowTop + (ROW_H - regular.lineHeight()) / 2,
					UiTheme.TEXT_3);
			return;
		}
		for (int i = 0; i < count; i++) {
			String name = watchlist.entries().get(i);
			int rowY = rowTop + i * ROW_H;
			drawRow(c, graphics, mc, name, x, rowY, w, i < count - 1);
		}
	}

	/** One watched account: accent tick, head, name. */
	private void drawRow(UiCanvas c, GuiGraphics graphics, Minecraft mc, String name,
			int x, int y, int w, boolean separator) {
		UiText medium = TYPE.medium();
		int barH = ROW_H - 8;
		Ui.surface(c, x + PAD, y + 4, BAR_W, barH, 1,
				Ui.withAlpha(UiTheme.ACCENT, 0xCC), Ui.withAlpha(UiTheme.ACCENT_DEEP, 0xCC));

		int headX = x + PAD + BAR_W + INNER;
		int headY = y + (ROW_H - HEAD) / 2;
		PlayerSkin skin = skinOf(mc, name);
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
			String initial = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
			Ui.drawCentered(c, medium, initial, headX + HEAD / 2,
					Ui.centerTextY(headY, HEAD, medium), UiTheme.TEXT);
		}

		int textX = headX + HEAD + INNER;
		int maxW = Math.max(0, x + w - PAD - textX);
		medium.draw(c, name, textX, Ui.centerTextY(y, ROW_H, medium), UiTheme.TEXT);

		if (separator) {
			Ui.fadeLine(c, textX, y + ROW_H - 1, maxW,
					Ui.withAlpha(UiTheme.BORDER, 0x40));
		}
	}

	/**
	 * The skin the tab list already holds for a name, or null.
	 *
	 * <p>Nothing is fetched here. A watched player who is not on the server has
	 * no entry, and inventing a UUID to ask Mojang for a skin would put a
	 * network request behind a HUD redraw - the one thing a per frame path must
	 * never do. Such a row simply draws the initial plate.</p>
	 */
	private static PlayerSkin skinOf(Minecraft mc, String name) {
		if (mc == null || mc.getConnection() == null || name == null) {
			return null;
		}
		try {
			PlayerInfo info = mc.getConnection().getPlayerInfo(name);
			return info == null ? null : info.getSkin();
		} catch (RuntimeException | LinkageError ignored) {
			return null;
		}
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
		json.addProperty("anchor", anchor());
		json.addProperty("offset_x", offsetX());
		json.addProperty("offset_y", offsetY());
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
