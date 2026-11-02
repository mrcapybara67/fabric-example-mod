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
 * <p>All elements share one chip: a glass plate with an accent bar, a muted
 * label and a bright value. Sharing the geometry is what keeps the HUD aligned
 * - the label and the value are centred in the same box, so they sit on the
 * same line whatever the two fonts resolve to, and every chip is the same
 * height no matter which readout it holds.</p>
 *
 * <h2>The drawing</h2>
 * A chip is built from the same four steps as every other surface in the
 * interface, in this order, because the order is what produces the depth:
 *
 * <ol>
 *   <li>a one pixel drop shadow below the plate;</li>
 *   <li>the plate: a vertical gradient in two steps, so it reads as a pane
 *       catching light at the top rather than as a flat rectangle;</li>
 *   <li>a hairline edge, brighter along the top and fading along the bottom -
 *       the two value steps a real piece of glass has;</li>
 *   <li>a specular band, only in the glass theme, sliding across the plate on a
 *       nine second cycle.</li>
 * </ol>
 *
 * <p>Everything is drawn on whole pixels and every corner radius comes from the
 * shared scale, so at a 4K window the plate is an exact rectangle rather than a
 * blurry one, and the text sits on the pixel grid its font expects.</p>
 *
 * <h2>Placement</h2>
 * The grid is a nine cell anchor. Elements in the same cell stack downwards in
 * registration order, so turning on more readouts can never stack two of them
 * on top of each other. Each chip then applies its own pixel nudge, which is how
 * a player moves one chip without disturbing the rest of the column.
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
	private static final int LABEL_GAP = 7;
	private static final int RADIUS = 6;
	private static final int CELLS = 9;
	/** How long the glass highlight takes to cross a chip. */
	private static final long SHEEN_PERIOD_MS = 9000L;

	// Scratch buffers: the HUD redraws every frame, so it must not allocate.
	private static HudElementModule[] scratchModules = new HudElementModule[16];
	private static String[] scratchValues = new String[16];
	private static String[] scratchLabels = new String[16];
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
			String label = hud.labelVisible() ? hud.label() : "";
			scratchModules[scratchSize] = hud;
			scratchValues[scratchSize] = value == null ? "" : value;
			scratchLabels[scratchSize] = label == null ? "" : label;
			scratchWidths[scratchSize] = chipWidth(regular, medium, scratchLabels[scratchSize],
					scratchValues[scratchSize], hud.accentBarVisible());
			scratchSize++;
		}
	}

	private static void grow() {
		int size = scratchModules.length * 2;
		scratchModules = java.util.Arrays.copyOf(scratchModules, size);
		scratchValues = java.util.Arrays.copyOf(scratchValues, size);
		scratchLabels = java.util.Arrays.copyOf(scratchLabels, size);
		scratchWidths = java.util.Arrays.copyOf(scratchWidths, size);
	}

	private static int chipWidth(UiText regular, UiText medium, String label, String value,
			boolean bar) {
		int width = PAD * 2;
		if (bar) {
			width += BAR_W + BAR_GAP;
		}
		if (!label.isEmpty()) {
			width += regular.width(label) + LABEL_GAP;
		}
		return width + medium.width(value);
	}

	private static void renderCell(UiCanvas c, UiText regular, UiText medium, int anchor,
			int screenW, int screenH) {
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

		float sheen = sheenPhase();
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
			drawChip(c, regular, medium, scratchLabels[i], scratchValues[i],
					x + hud.offsetX(), y + hud.offsetY(), w, hud, sheen);
			y += CHIP_H + GAP;
		}
	}

	/** Draws one chip: shadow, glass plate, hairline, accent bar, text. */
	private static void drawChip(UiCanvas c, UiText regular, UiText medium, String label,
			String value, int x, int y, int w, HudElementModule hud, float sheen) {
		int alpha = hud.backgroundAlpha();
		boolean bar = hud.accentBarVisible();
		int accent = hud.accentArgb();
		if (accent == 0) {
			accent = UiTheme.ACCENT;
		}

		if (alpha > 0) {
			// One pixel of shadow, not a blur: the chip is a small object and a
			// wide shadow under a small object reads as a smudge.
			Ui.roundRect(c, x + 1, y + 2, w, CHIP_H, RADIUS, 0x33000000);
			Ui.surface(c, x, y, w, CHIP_H, RADIUS,
					Ui.withAlpha(UiTheme.PANEL_ALT, alpha),
					Ui.withAlpha(UiTheme.PANEL_BOTTOM, Math.round(alpha * 0.94f)));
			Ui.surfaceEdge(c, x, y, w, CHIP_H, RADIUS,
					Ui.withAlpha(UiTheme.BORDER, Math.round(alpha * 0.80f)),
					Ui.withAlpha(UiTheme.BORDER, Math.round(alpha * 0.28f)));
		}

		if (UiTheme.isGlass()) {
			// The specular band and the top highlight are what make the plate
			// read as glass instead of as a grey rectangle. Only the glass theme
			// gets them: Sakura is paper, and a highlight on paper looks wet.
			Ui.sheen(c, x, y, w, CHIP_H, RADIUS, sheen);
			Ui.surface(c, x + RADIUS, y, Math.max(0, w - RADIUS * 2), 1, 0,
					Ui.withAlpha(0xFFFFFF, Math.round(alpha * 0.10f)));
		}

		int textX = x + PAD;
		if (bar) {
			int barH = CHIP_H - 10;
			// The bar is a gradient, deep at the bottom and light at the top, so
			// it has a direction even when the chip is only eighteen pixels tall.
			Ui.surface(c, x + PAD, y + (CHIP_H - barH) / 2, BAR_W, barH, 1,
					accent, UiTheme.ACCENT_DEEP);
			textX += BAR_W + BAR_GAP;
		}
		if (!label.isEmpty()) {
			regular.draw(c, label, textX, Ui.centerTextY(y, CHIP_H, regular), UiTheme.TEXT_3);
			textX += regular.width(label) + LABEL_GAP;
		}
		medium.draw(c, value, textX, Ui.centerTextY(y, CHIP_H, medium), UiTheme.TEXT);
	}

	/** Where the specular band is, 0..1, on a slow cycle. */
	private static float sheenPhase() {
		return (System.currentTimeMillis() % SHEEN_PERIOD_MS) / (float) SHEEN_PERIOD_MS;
	}
}
