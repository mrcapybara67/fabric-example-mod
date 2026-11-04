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
 * Draws every enabled HUD element, and lays them out for the editor.
 *
 * <p>All elements share one chip: a glass plate with an accent bar, a muted
 * label and a bright value. Sharing the geometry is what keeps the HUD aligned
 * - the label and the value are centred in the same box, so they sit on the
 * same line whatever the two fonts resolve to, and every chip is the same
 * height no matter which readout it holds.</p>
 *
 * <h2>One layout, two readers</h2>
 * The position of a chip is computed once per frame by {@link #prepare} and then
 * read by whoever needs it: the renderer to draw it, and the drag editor to draw
 * a handle around it and to work out where the pointer moved it to. That is the
 * reason the layout is public and the drawing is not - an editor with its own
 * copy of the arrangement maths would drift from the real thing the first time
 * either changed, and a handle that is two pixels off the chip it moves is worse
 * than no editor at all.
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
	private static int[] scratchBaseX = new int[16];
	private static int[] scratchBaseY = new int[16];
	private static int scratchSize;

	// Panels are laid out by the same pass as the chips, into their own slots.
	// A panel has its own size, so its width and height are stored beside it.
	private static HudPanel[] scratchPanels = new HudPanel[4];
	private static int[] panelWidths = new int[4];
	private static int[] panelHeights = new int[4];
	private static int[] panelBaseX = new int[4];
	private static int[] panelBaseY = new int[4];
	private static int scratchPanelsSize;

	private HudRenderer() {
	}

	/** Draws the whole HUD. Called once per frame from the HUD hook. */
	public static void render(GuiGraphics graphics, int screenW, int screenH) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.player == null || mc.level == null) {
			return;
		}
		prepare(mc, screenW, screenH, TYPE.regular(), TYPE.medium());
		drawPrepared(graphics);
	}

	/**
	 * Draws the chips the last {@link #prepare} laid out.
	 *
	 * <p>Separate from {@link #render} so the drag editor can draw the very same
	 * chips over its own scrim: a chip that is dimmed under the editor's shading
	 * is a chip the player cannot judge while placing it, which defeats the
	 * point of dragging it around the scene it has to stay readable on.</p>
	 */
	public static void drawPrepared(GuiGraphics graphics) {
		if (scratchSize == 0) {
			return;
		}
		UiCanvas canvas = McCanvas.of(graphics);
		UiText regular = TYPE.regular();
		UiText medium = TYPE.medium();
		float sheen = sheenPhase();
		for (int i = 0; i < scratchSize; i++) {
			HudElementModule hud = scratchModules[i];
			drawChip(canvas, regular, medium, scratchLabels[i], scratchValues[i],
					scratchBaseX[i] + hud.offsetX(), scratchBaseY[i] + hud.offsetY(),
					scratchWidths[i], hud, sheen);
		}
		// Panels last, so a box always paints over the chips it shares a cell
		// with instead of being cut into by one.
		for (int i = 0; i < scratchPanelsSize; i++) {
			HudPanel panel = scratchPanels[i];
			try {
				panel.renderPanel(graphics, panelBaseX[i] + panel.offsetX(),
						panelBaseY[i] + panel.offsetY(), panelWidths[i], panelHeights[i]);
			} catch (Throwable failure) {
				// The HUD is drawn on the first frame in a world; one broken panel
				// must not take the whole HUD - or the frame - with it.
				com.capy.client.util.Guard.report("hud panel", failure);
			}
		}
	}

	// ----------------------------------------------------------------- layout

	/** Lays out the enabled chips for the current frame, using the shared fonts. */
	public static void prepare(int screenW, int screenH) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null) {
			scratchSize = 0;
			return;
		}
		UiText regular = TYPE.regular();
		prepare(mc, screenW, screenH, regular, TYPE.medium());
	}

	/**
	 * Gathers the enabled elements, measures them and gives each one its cell
	 * position. The offsets are deliberately <em>not</em> applied here: the
	 * editor needs the base position to compute a new offset from, and the
	 * renderer adds the offset when it draws.
	 */
	private static void prepare(Minecraft mc, int screenW, int screenH, UiText regular, UiText medium) {
		scratchSize = 0;
		scratchPanelsSize = 0;
		for (Module module : ModuleManager.get().modules()) {
			if (!module.isEnabled()) {
				continue;
			}
			if (module instanceof HudElementModule hud) {
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
			} else if (module instanceof HudPanel panel) {
				if (scratchPanelsSize == scratchPanels.length) {
					growPanels();
				}
				// A panel with no size this frame (an empty list, no player) still
				// needs a slot, or the editor could not select it to move it.
				int w = Math.max(0, panel.panelWidth(mc));
				int h = Math.max(0, panel.panelHeight(mc));
				scratchPanels[scratchPanelsSize] = panel;
				panelWidths[scratchPanelsSize] = w;
				panelHeights[scratchPanelsSize] = h;
				scratchPanelsSize++;
			}
		}
		if (scratchSize == 0 && scratchPanelsSize == 0) {
			return;
		}
		// One cell at a time: the column is the anchor modulo three, the row is
		// the anchor divided by three, and the contents of a cell stack
		// downwards from the edge that cell belongs to. Chips and panels share
		// the stack, so the two kinds are laid out as one column and cannot
		// land on top of each other.
		for (int anchor = 0; anchor < CELLS; anchor++) {
			int chips = 0;
			int panels = 0;
			int chipH = 0;
			int panelH = 0;
			for (int i = 0; i < scratchSize; i++) {
				if (scratchModules[i].anchor() == anchor) {
					chips++;
					chipH += CHIP_H;
				}
			}
			for (int i = 0; i < scratchPanelsSize; i++) {
				if (scratchPanels[i].anchor() == anchor) {
					panels++;
					panelH += panelHeights[i];
				}
			}
			if (chips == 0 && panels == 0) {
				continue;
			}
			int gaps = Math.max(0, chips - 1) + Math.max(0, panels - 1)
					+ (chips > 0 && panels > 0 ? 1 : 0);
			int totalH = chipH + panelH + gaps * GAP;
			int column = anchor % 3;
			int row = anchor / 3;
			int y = row == 0 ? MARGIN : row == 1 ? (screenH - totalH) / 2 : screenH - MARGIN - totalH;
			for (int i = 0; i < scratchSize; i++) {
				if (scratchModules[i].anchor() != anchor) {
					continue;
				}
				scratchBaseX[i] = columnX(column, screenW, scratchWidths[i]);
				scratchBaseY[i] = y;
				y += CHIP_H + GAP;
			}
			for (int i = 0; i < scratchPanelsSize; i++) {
				if (scratchPanels[i].anchor() != anchor) {
					continue;
				}
				panelBaseX[i] = columnX(column, screenW, panelWidths[i]);
				panelBaseY[i] = y;
				y += panelHeights[i] + GAP;
			}
		}
	}

	/** Left, centre or right edge of a column, for a box of the given width. */
	private static int columnX(int column, int screenW, int w) {
		return column == 0 ? MARGIN
				: column == 1 ? (screenW - w) / 2 : screenW - MARGIN - w;
	}

	private static void growPanels() {
		int size = scratchPanels.length * 2;
		scratchPanels = java.util.Arrays.copyOf(scratchPanels, size);
		panelWidths = java.util.Arrays.copyOf(panelWidths, size);
		panelHeights = java.util.Arrays.copyOf(panelHeights, size);
		panelBaseX = java.util.Arrays.copyOf(panelBaseX, size);
		panelBaseY = java.util.Arrays.copyOf(panelBaseY, size);
	}

	private static void grow() {
		int size = scratchModules.length * 2;
		scratchModules = java.util.Arrays.copyOf(scratchModules, size);
		scratchValues = java.util.Arrays.copyOf(scratchValues, size);
		scratchLabels = java.util.Arrays.copyOf(scratchLabels, size);
		scratchWidths = java.util.Arrays.copyOf(scratchWidths, size);
		scratchBaseX = java.util.Arrays.copyOf(scratchBaseX, size);
		scratchBaseY = java.util.Arrays.copyOf(scratchBaseY, size);
	}

	// ------------------------------------------------------- layout readings

	/** How many chips the last {@link #prepare} laid out. */
	public static int slotCount() {
		return scratchSize;
	}

	/** The module behind a laid out chip. */
	public static HudElementModule slotModule(int index) {
		return scratchModules[index];
	}

	/** The chip's cell position, before its own nudge. */
	public static int slotBaseX(int index) {
		return scratchBaseX[index];
	}

	public static int slotBaseY(int index) {
		return scratchBaseY[index];
	}

	/** Where the chip is actually drawn, nudge included. */
	public static int slotX(int index) {
		return scratchBaseX[index] + scratchModules[index].offsetX();
	}

	public static int slotY(int index) {
		return scratchBaseY[index] + scratchModules[index].offsetY();
	}

	public static int slotWidth(int index) {
		return scratchWidths[index];
	}

	public static int chipHeight() {
		return CHIP_H;
	}

	/** How many panels the last {@link #prepare} laid out. */
	public static int panelCount() {
		return scratchPanelsSize;
	}

	/** The panel behind a laid out box. */
	public static HudPanel panelAt(int index) {
		return scratchPanels[index];
	}

	public static int panelBaseX(int index) {
		return panelBaseX[index];
	}

	public static int panelBaseY(int index) {
		return panelBaseY[index];
	}

	/** Where the panel is actually drawn, nudge included. */
	public static int panelX(int index) {
		return panelBaseX[index] + scratchPanels[index].offsetX();
	}

	public static int panelY(int index) {
		return panelBaseY[index] + scratchPanels[index].offsetY();
	}

	public static int panelWidth(int index) {
		return panelWidths[index];
	}

	public static int panelHeight(int index) {
		return panelHeights[index];
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

	// ---------------------------------------------------------------- drawing

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
