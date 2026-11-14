package com.capy.client.module.modules.hud;

import com.capy.client.config.ConfigValues;
import com.capy.client.gui.McCanvas;
import com.capy.client.gui.McType;
import com.capy.client.gui.Ui;
import com.capy.client.gui.UiCanvas;
import com.capy.client.gui.UiText;
import com.capy.client.gui.UiTheme;
import com.capy.client.gui.UiType;
import com.capy.client.module.Category;
import com.capy.client.module.Configurable;
import com.capy.client.module.Module;
import com.capy.client.module.SliderSetting;
import com.capy.client.module.Tickable;
import com.capy.client.module.modules.esp.FinderSignals;
import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The live finder list: one row per signal, nearest first.
 *
 * <p>The panel exists only while a finder has something to report. It is not a
 * permanent empty box: an empty finder list would take attention away from the
 * world without telling the player anything. When two different finders point
 * at the same area, their rows become one stronger "2 signals" row, which is the
 * moment the evidence is worth acting on.</p>
 */
public final class FinderListHudModule extends Module implements Configurable, HudPanel, Tickable {
	private static final UiType TYPE = new McType();
	private static final int PAD = 8;
	private static final int HEADER_H = 15;
	private static final int ROW_H = 16;
	private static final int RADIUS = 10;
	private static final int BAR_W = 2;
	private static final int DOT = 3;
	private static final int MIN_W = 176;
	private static final int MAX_W = 430;
	private static final int MAX_ROWS = 8;
	private static final int MAX_NUDGE = HudElementModule.MAX_NUDGE;
	private static final long REFRESH_MS = 50L;

	private final SliderSetting anchor = setting(new SliderSetting("anchor", HudElementModule.ANCHOR_TOP_LEFT,
			HudElementModule.ANCHOR_TOP_LEFT, HudElementModule.ANCHOR_BOTTOM_RIGHT));
	private final SliderSetting offsetX = setting(new SliderSetting("offset_x", 0, -MAX_NUDGE, MAX_NUDGE));
	private final SliderSetting offsetY = setting(new SliderSetting("offset_y", 0, -MAX_NUDGE, MAX_NUDGE));

	private List<FinderSignals.Group> rows = List.of();
	private Object lastLevel;
	private long lastRefreshAt;
	private boolean refreshed;

	public FinderListHudModule() {
		super(Category.HUD, "finder_list");
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!isEnabled()) {
			return;
		}
		refresh(mc);
	}

	private void ensureRows(Minecraft mc) {
		long now = System.currentTimeMillis();
		if (!refreshed || mc == null || mc.level != lastLevel || now - lastRefreshAt >= REFRESH_MS) {
			refresh(mc);
		}
	}

	private void refresh(Minecraft mc) {
		refreshed = true;
		lastLevel = mc == null ? null : mc.level;
		lastRefreshAt = System.currentTimeMillis();
		if (mc == null || mc.player == null || mc.level == null) {
			rows = List.of();
			return;
		}
		List<FinderSignals.Group> all = FinderSignals.collect(mc);
		rows = all.size() > MAX_ROWS ? List.copyOf(all.subList(0, MAX_ROWS)) : all;
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
		if (rows.isEmpty()) {
			return 0;
		}
		UiText regular = TYPE.regular();
		UiText medium = TYPE.medium();
		int widest = PAD * 2 + DOT * 2 + 6;
		for (FinderSignals.Group row : rows) {
			widest = Math.max(widest, PAD * 2 + DOT * 2 + 6
					+ medium.width(row.label()) + 7 + regular.width(rest(row)));
		}
		int header = PAD * 2 + BAR_W + 6 + medium.width("FINDER LIST") + 24;
		return Math.max(MIN_W, Math.min(MAX_W, Math.max(widest, header)));
	}

	@Override
	public int panelHeight(Minecraft mc) {
		ensureRows(mc);
		if (rows.isEmpty()) {
			return 0;
		}
		return PAD * 2 + HEADER_H + rows.size() * ROW_H;
	}

	// -------------------------------------------------------------------- draw

	@Override
	public void renderPanel(GuiGraphics graphics, int x, int y, int w, int h) {
		ensureRows(Minecraft.getInstance());
		if (w <= 0 || h <= 0 || rows.isEmpty()) {
			return;
		}
		UiCanvas c = McCanvas.of(graphics);
		UiText regular = TYPE.regular();
		UiText medium = TYPE.medium();
		boolean combined = false;
		for (FinderSignals.Group row : rows) {
			combined |= row.combined();
		}
		int accent = combined ? UiTheme.ACCENT_2 : UiTheme.ACCENT;

		Ui.roundRect(c, x + 1, y + 2, w, h, RADIUS, 0x33000000);
		Ui.surface(c, x, y, w, h, RADIUS, UiTheme.PANEL_ALT, UiTheme.PANEL_BOTTOM);
		Ui.surfaceEdge(c, x, y, w, h, RADIUS, Ui.withAlpha(UiTheme.BORDER, 0xC0),
				Ui.withAlpha(UiTheme.BORDER, 0x44));
		Ui.sheen(c, x, y, w, h, RADIUS, (System.currentTimeMillis() % 9000L) / 9000f);
		Ui.surface(c, x + RADIUS, y, Math.max(0, w - RADIUS * 2), 1, 0,
				Ui.withAlpha(0xFFFFFF, 0x18));

		int headerY = y + PAD;
		Ui.surface(c, x + PAD, headerY + (HEADER_H - 8) / 2, BAR_W, 8, 1, accent, UiTheme.ACCENT_DEEP);
		medium.draw(c, "FINDER LIST", x + PAD + BAR_W + 6,
				headerY + Math.max(0, (HEADER_H - medium.lineHeight()) / 2), UiTheme.TEXT_2);
		String count = Integer.toString(rows.size());
		regular.draw(c, count, x + w - PAD - regular.width(count),
				headerY + Math.max(0, (HEADER_H - regular.lineHeight()) / 2), UiTheme.TEXT_3);

		int rowTop = headerY + HEADER_H;
		for (int i = 0; i < rows.size(); i++) {
			FinderSignals.Group row = rows.get(i);
			int rowY = rowTop + i * ROW_H;
			if (row.combined()) {
				Ui.surface(c, x + PAD - 2, rowY, w - (PAD - 2) * 2, ROW_H - 1, 4,
						Ui.withAlpha(UiTheme.ACCENT, 0x1A));
			}
			int dotX = x + PAD + DOT;
			int dotY = rowY + ROW_H / 2 - 1;
			Ui.circle(c, dotX, dotY, DOT, row.color());

			int textX = x + PAD + DOT * 2 + 6;
			int labelColor = row.combined() ? UiTheme.TEXT : row.color();
			String label = row.label();
			int labelRoom = x + w - PAD - textX;
			int labelWidth = medium.width(label);
			if (labelWidth > labelRoom) {
				Ui.drawEllipsized(c, medium, label, textX,
						rowY + (ROW_H - medium.lineHeight()) / 2, labelRoom, labelColor);
				continue;
			}
			medium.draw(c, label, textX, rowY + (ROW_H - medium.lineHeight()) / 2, labelColor);
			int restX = textX + labelWidth + 7;
			int restRoom = x + w - PAD - restX;
			if (restRoom > 0) {
				Ui.drawEllipsized(c, regular, rest(row), restX,
						rowY + (ROW_H - regular.lineHeight()) / 2, restRoom,
						row.combined() ? UiTheme.TEXT_2 : UiTheme.TEXT_3);
			}
		}
	}

	private static String rest(FinderSignals.Group row) {
		return "  " + row.chunkX() + ", " + row.chunkZ() + "  " + row.distanceBlocks() + "b  " + row.reason();
	}

	@Override
	public String panelLabel() {
		return "Finder list";
	}

	// ------------------------------------------------------------ persistence

	@Override
	public void loadConfig(JsonObject json) {
		anchor.set(ConfigValues.intValue(json, "anchor", HudElementModule.ANCHOR_TOP_LEFT));
		offsetX.set(ConfigValues.intValue(json, "offset_x", 0));
		offsetY.set(ConfigValues.intValue(json, "offset_y", 0));
		refreshed = false;
	}

	@Override
	public void saveConfig(JsonObject json) {
		json.addProperty("anchor", anchor());
		json.addProperty("offset_x", offsetX());
		json.addProperty("offset_y", offsetY());
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
