package com.capy.client.module.modules.hud;

import com.capy.client.gui.McCanvas;
import com.capy.client.gui.McType;
import com.capy.client.gui.Ui;
import com.capy.client.gui.UiCanvas;
import com.capy.client.gui.UiText;
import com.capy.client.gui.UiTheme;
import com.capy.client.gui.UiType;
import com.capy.client.module.Module;
import com.capy.client.module.ModuleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Draws every enabled HUD element.
 *
 * All elements share one chip: a small glass plate with an accent bar, a muted
 * label and a bright value. Sharing the geometry is what keeps the HUD aligned
 * - the label and the value are centred in the same box, so they sit on the
 * same line whatever the two fonts resolve to, and every chip is the same
 * height no matter which readout it holds.
 *
 * Placement is a nine cell grid. Elements in the same cell stack downwards in
 * registration order, so turning on more readouts can never stack two of them
 * on top of each other.
 */
public final class HudRenderer {
	private static final UiType TYPE = new McType();

	private static final int CHIP_H = 18;
	private static final int PAD = 8;
	private static final int MARGIN = 8;
	private static final int GAP = 3;
	/** Width of the accent bar and the space it needs. */
	private static final int BAR_W = 2;
	private static final int BAR_GAP = 6;
	/** Gap between the muted label and the value. */
	private static final int LABEL_GAP = 8;
	private static final int RADIUS = 7;
	private static final int CELLS = 9;

	// Scratch buffers: the HUD redraws every frame, so it must not allocate.
	private static HudElementModule[] scratchModules = new HudElementModule[16];
	private static String[] scratchValues = new String[16];
	private static int[] scratchWidths = new int[16];
	private static int scratchSize;

	private HudRenderer() {
	}

	/** Draws the whole HUD. Called once per frame from the HUD hook. */
	public static void render(GuiGraphics graphics, int screenW, int screenH) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.player == null || mc.level == null) {
			return;
		}
		UiText regular = TYPE.regular();
		UiText medium = TYPE.medium();
		gather(mc, regular, medium);
		if (scratchSize == 0) {
			return;
		}
		UiCanvas canvas = McCanvas.of(graphics);
		for (int anchor = 0; anchor < CELLS; anchor++) {
			renderCell(canvas, regular, medium, anchor, screenW, screenH);
		}
	}

	/** Collects the enabled elements, their values and their widths. */
	private static void gather(Minecraft mc, UiText regular, UiText medium) {
		scratchSize = 0;
		for (Module module : ModuleManager.get().modules()) {
			if (!(module instanceof HudElementModule hud) || !hud.isEnabled()) {
				continue;
			}
			if (scratchSize == scratchModules.length) {
				grow();
			}
			String value = hud.value(mc);
			scratchModules[scratchSize] = hud;
			scratchValues[scratchSize] = value == null ? "" : value;
			scratchWidths[scratchSize] = chipWidth(regular, medium, hud.label(), scratchValues[scratchSize]);
			scratchSize++;
		}
	}

	private static void grow() {
		int size = scratchModules.length * 2;
		scratchModules = java.util.Arrays.copyOf(scratchModules, size);
		scratchValues = java.util.Arrays.copyOf(scratchValues, size);
		scratchWidths = java.util.Arrays.copyOf(scratchWidths, size);
	}

	private static int chipWidth(UiText regular, UiText medium, String label, String value) {
		int width = PAD * 2 + BAR_W + BAR_GAP;
		if (!label.isEmpty()) {
			width += regular.width(label) + LABEL_GAP;
		}
		return width + medium.width(value);
	}

	private static void renderCell(UiCanvas c, UiText regular, UiText medium, int anchor, int screenW, int screenH) {
		int count = 0;
		int totalH = 0;
		for (int i = 0; i < scratchSize; i++) {
			if (scratchModules[i].anchor() != anchor) {
				continue;
			}
			count++;
			totalH += CHIP_H + GAP;
		}
		if (count == 0) {
			return;
		}
		totalH -= GAP;

		int column = anchor % 3;
		int row = anchor / 3;
		int baseY;
		if (row == 0) {
			baseY = MARGIN;
		} else if (row == 1) {
			baseY = (screenH - totalH) / 2;
		} else {
			baseY = screenH - MARGIN - totalH;
		}

		int y = baseY;
		for (int i = 0; i < scratchSize; i++) {
			HudElementModule hud = scratchModules[i];
			if (hud.anchor() != anchor) {
				continue;
			}
			int w = scratchWidths[i];
			int x;
			if (column == 0) {
				x = MARGIN;
			} else if (column == 1) {
				x = (screenW - w) / 2;
			} else {
				x = screenW - MARGIN - w;
			}
			drawChip(c, regular, medium, hud.label(), scratchValues[i], x + hud.offsetX(), y + hud.offsetY(), w);
			y += CHIP_H + GAP;
		}
	}

	/** Draws one chip: shadow, glass plate, hairline, accent bar, text. */
	private static void drawChip(UiCanvas c, UiText regular, UiText medium, String label, String value,
			int x, int y, int w) {
		Ui.roundRect(c, x + 1, y + 2, w, CHIP_H, RADIUS, 0x33000000);
		Ui.surface(c, x, y, w, CHIP_H, RADIUS,
				Ui.withAlpha(UiTheme.PANEL_ALT, 0xF2),
				Ui.withAlpha(UiTheme.PANEL_BOTTOM, 0xE4));
		Ui.surfaceEdge(c, x, y, w, CHIP_H, RADIUS,
				Ui.withAlpha(UiTheme.BORDER, 0xC8),
				Ui.withAlpha(UiTheme.BORDER, 0x50));

		// The accent bar marks the chip as part of the client, exactly like the
		// leading bar on an enabled module card.
		int barH = CHIP_H - 10;
		Ui.surface(c, x + PAD, y + (CHIP_H - barH) / 2, BAR_W, barH, 1,
				UiTheme.ACCENT, UiTheme.ACCENT_DEEP);

		int textX = x + PAD + BAR_W + BAR_GAP;
		if (!label.isEmpty()) {
			regular.draw(c, label, textX, Ui.centerTextY(y, CHIP_H, regular), UiTheme.TEXT_3);
			textX += regular.width(label) + LABEL_GAP;
		}
		medium.draw(c, value, textX, Ui.centerTextY(y, CHIP_H, medium), UiTheme.TEXT);
	}
}
