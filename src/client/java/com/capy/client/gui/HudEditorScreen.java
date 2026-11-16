package com.capy.client.gui;

import com.capy.client.config.ClientConfig;
import com.capy.client.keybind.ClientKeybinds;
import com.capy.client.module.Module;
import com.capy.client.module.ModuleManager;
import com.capy.client.module.modules.hud.HudElementModule;
import com.capy.client.module.modules.hud.HudPanel;
import com.capy.client.module.modules.hud.HudRenderer;
import com.capy.client.stealth.CapyText;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The HUD editor: drag a chip or a panel, select it, and use the inspector to
 * snap it back to any of the nine cells.
 *
 * <p>The arrangement is not reimplemented here. {@link HudRenderer#prepare}
 * lays the HUD out exactly as the game will, and every handle, selection and
 * drag is read back from that same layout. The toolbar and the inspector are
 * editor-only chrome; they never change the geometry of the HUD itself.</p>
 *
 * <p>Interaction contract:</p>
 * <ul>
 *   <li>left-click a chip/panel: select it and start dragging;</li>
 *   <li>left-click an anchor cell: snap the selection to that cell;</li>
 *   <li>right-click a chip/panel: reset it to its default cell;</li>
 *   <li>Reset all: put every HUD surface back on its default cell;</li>
 *   <li>Done or Escape: save and close.</li>
 * </ul>
 */
public final class HudEditorScreen extends Screen {
	private static final UiType TYPE = new McType();
	private static final int MARGIN = 12;
	private static final int TOOLBAR_H = 54;
	private static final int INSPECTOR_H = 68;
	private static final int BUTTON_H = 24;

	/** The chip or panel being dragged, or null. */
	private Object dragging;
	/** The chip or panel shown in the inspector, or null. */
	private Object selected;
	/** Where inside the thing the drag started, in pixels. */
	private int grabX;
	private int grabY;

	private boolean soundedOpen;
	private boolean soundedClose;

	private int toolbarX;
	private int toolbarY;
	private int toolbarW;
	private int doneX;
	private int doneY;
	private int doneW = 54;
	private int resetAllX;
	private int resetAllY;
	private int resetAllW = 86;
	private int inspectorX;
	private int inspectorY;
	private int inspectorW;
	private int resetSelectedX;
	private int resetSelectedY;
	private int resetSelectedW = 62;
	private final int[] anchorX = new int[9];
	private final int[] anchorY = new int[9];
	private int anchorSize = 14;

	public HudEditorScreen() {
		super(Component.literal(CapyText.get("capy.hud.editor", "HUD Editor")));
	}

	@Override
	protected void init() {
		super.init();
		if (!soundedOpen) {
			soundedOpen = true;
			GuiSounds.open();
		}
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		layoutChrome();
		graphics.fill(0, 0, this.width, this.height, 0x66101018);
		HudRenderer.prepare(this.width, this.height);
		UiCanvas canvas = McCanvas.of(graphics);
		Ui.vignette(canvas, this.width, this.height, 1f);
		HudRenderer.drawPrepared(graphics);

		// The chrome owns the top and bottom strips. A HUD surface under it is
		// visible but not selectable, which keeps the toolbar reliable.
		int hoveredChip = -1;
		int hoveredPanel = -1;
		boolean overChrome = inside(mouseX, mouseY, toolbarX, toolbarY, toolbarW, TOOLBAR_H)
				|| inside(mouseX, mouseY, inspectorX, inspectorY, inspectorW, INSPECTOR_H);
		if (!overChrome) {
			for (int i = HudRenderer.panelCount() - 1; i >= 0; i--) {
				int x = HudRenderer.panelX(i);
				int y = HudRenderer.panelY(i);
				int w = HudRenderer.panelWidth(i);
				int h = HudRenderer.panelHeight(i);
				boolean hot = HudRenderer.panelAt(i) == selected;
				boolean near = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
				if (near) {
					hoveredPanel = i;
				}
				drawHandle(canvas, x, y, w, h, hot, near, false);
			}

			for (int i = 0; i < HudRenderer.slotCount(); i++) {
				HudElementModule module = HudRenderer.slotModule(i);
				int x = HudRenderer.slotX(i);
				int y = HudRenderer.slotY(i);
				int w = HudRenderer.slotWidth(i);
				int h = HudRenderer.chipHeight();
				boolean hot = module == selected;
				boolean near = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
				if (near && hoveredPanel < 0) {
					hoveredChip = i;
				}
				drawHandle(canvas, x, y, w, h, hot, near, !hot && hoveredPanel >= 0);
			}
		} else {
			for (int i = 0; i < HudRenderer.panelCount(); i++) {
				drawHandle(canvas, HudRenderer.panelX(i), HudRenderer.panelY(i),
						HudRenderer.panelWidth(i), HudRenderer.panelHeight(i),
						HudRenderer.panelAt(i) == selected, false, false);
			}
			for (int i = 0; i < HudRenderer.slotCount(); i++) {
				drawHandle(canvas, HudRenderer.slotX(i), HudRenderer.slotY(i),
						HudRenderer.slotWidth(i), HudRenderer.chipHeight(),
						HudRenderer.slotModule(i) == selected, false, false);
			}
		}

		drawToolbar(canvas, mouseX, mouseY);
		drawInspector(canvas, mouseX, mouseY, hoveredChip, hoveredPanel);
	}

	private void layoutChrome() {
		toolbarW = Math.max(280, Math.min(680, this.width - MARGIN * 2));
		toolbarX = (this.width - toolbarW) / 2;
		toolbarY = MARGIN;
		doneW = 54;
		doneX = toolbarX + toolbarW - MARGIN - doneW;
		doneY = toolbarY + (TOOLBAR_H - BUTTON_H) / 2;
		resetAllW = 86;
		resetAllX = doneX - 8 - resetAllW;
		resetAllY = doneY;
		inspectorW = Math.max(280, Math.min(680, this.width - MARGIN * 2));
		inspectorX = (this.width - inspectorW) / 2;
		inspectorY = this.height - INSPECTOR_H - MARGIN;
		resetSelectedW = 62;
		resetSelectedX = inspectorX + inspectorW - MARGIN - resetSelectedW;
		resetSelectedY = inspectorY + (INSPECTOR_H - BUTTON_H) / 2;
		anchorSize = 14;
		int gap = 3;
		int grid = anchorSize * 3 + gap * 2;
		int gridX = resetSelectedX - 12 - grid;
		int gridY = inspectorY + (INSPECTOR_H - grid) / 2;
		for (int i = 0; i < 9; i++) {
			anchorX[i] = gridX + (i % 3) * (anchorSize + gap);
			anchorY[i] = gridY + (i / 3) * (anchorSize + gap);
		}
	}

	private void drawToolbar(UiCanvas c, int mouseX, int mouseY) {
		Ui.shadow(c, toolbarX, toolbarY, toolbarW, TOOLBAR_H, UiTheme.RADIUS_LG);
		Ui.surface(c, toolbarX, toolbarY, toolbarW, TOOLBAR_H, UiTheme.RADIUS_LG,
				UiTheme.PANEL_TOP, UiTheme.PANEL_BOTTOM);
		Ui.outline(c, toolbarX, toolbarY, toolbarW, TOOLBAR_H, UiTheme.RADIUS_LG, UiTheme.BORDER);
		Ui.sheen(c, toolbarX, toolbarY, toolbarW, TOOLBAR_H, UiTheme.RADIUS_LG, sheen());
		Ui.surface(c, toolbarX + 16, toolbarY + 14, 3, TOOLBAR_H - 28, 1,
				UiTheme.ACCENT, UiTheme.ACCENT_DEEP);
		UiText medium = TYPE.medium();
		UiText regular = TYPE.regular();
		medium.draw(c, "HUD EDITOR", toolbarX + 26, toolbarY + 10, UiTheme.TEXT);
		int hintRoom = Math.max(0, resetAllX - (toolbarX + 26) - 12);
		Ui.drawEllipsized(c, regular,
				"Click or Tab to select  ·  drag to move  ·  right-click resets",
				toolbarX + 26, toolbarY + 29, hintRoom, UiTheme.TEXT_3);
		drawButton(c, resetAllX, resetAllY, resetAllW, BUTTON_H, "Reset all",
				inside(mouseX, mouseY, resetAllX, resetAllY, resetAllW, BUTTON_H), false);
		drawButton(c, doneX, doneY, doneW, BUTTON_H, "Done",
				inside(mouseX, mouseY, doneX, doneY, doneW, BUTTON_H), true);
	}

	private void drawInspector(UiCanvas c, int mouseX, int mouseY, int hoveredChip, int hoveredPanel) {
		Ui.shadow(c, inspectorX, inspectorY, inspectorW, INSPECTOR_H, UiTheme.RADIUS_LG);
		Ui.surface(c, inspectorX, inspectorY, inspectorW, INSPECTOR_H, UiTheme.RADIUS_LG,
				UiTheme.PANEL_TOP, UiTheme.PANEL_BOTTOM);
		Ui.outline(c, inspectorX, inspectorY, inspectorW, INSPECTOR_H, UiTheme.RADIUS_LG, UiTheme.BORDER);
		Ui.sheen(c, inspectorX, inspectorY, inspectorW, INSPECTOR_H, UiTheme.RADIUS_LG, sheen());
		UiText regular = TYPE.regular();
		UiText medium = TYPE.medium();
		if (selected == null) {
			String hint = hoveredPanel >= 0 || hoveredChip >= 0
					? "Click the highlighted surface to select it"
					: "Click a surface, or press Tab to select";
			int rightRoom = inspectorW >= 460 ? 126 : 0;
			if (rightRoom > 0) {
				Ui.drawRightAligned(c, regular, "Tab cycles  ·  Esc saves",
						inspectorX + inspectorW - 18,
						Ui.centerTextY(inspectorY, INSPECTOR_H, regular), UiTheme.TEXT_3);
			}
			Ui.drawEllipsized(c, medium, hint, inspectorX + 18,
					Ui.centerTextY(inspectorY, INSPECTOR_H, medium),
					Math.max(0, inspectorW - 36 - rightRoom), UiTheme.TEXT_2);
			return;
		}

		String label = labelOf(selected);
		String kind = selected instanceof HudPanel ? "PANEL" : "CHIP";
		medium.draw(c, label, inspectorX + 18, inspectorY + 13, UiTheme.TEXT);
		UiText meta = regular;
		int offsetX = selected instanceof HudElementModule hud ? hud.offsetX()
				: selected instanceof HudPanel panel ? panel.offsetX() : 0;
		int offsetY = selected instanceof HudElementModule hud ? hud.offsetY()
				: selected instanceof HudPanel panel ? panel.offsetY() : 0;
		meta.draw(c, kind + "  ·  x " + offsetX + "  y " + offsetY,
				inspectorX + 18, inspectorY + 32, UiTheme.TEXT_3);

		int active = anchorOf(selected);
		// The grid fills the inspector's height, so the caption sits beside it
		// rather than on top of its first row.
		Ui.drawRightAligned(c, meta, "ANCHOR", anchorX[0] - 7,
				anchorY[0] + (anchorSize - meta.lineHeight()) / 2, UiTheme.TEXT_3);
		for (int i = 0; i < 9; i++) {
			boolean hot = inside(mouseX, mouseY, anchorX[i], anchorY[i], anchorSize, anchorSize);
			boolean on = i == active;
			Ui.surface(c, anchorX[i], anchorY[i], anchorSize, anchorSize, 4,
					on ? Ui.withAlpha(UiTheme.ACCENT, 0xC8) : hot ? UiTheme.PANEL_ALT : UiTheme.TRACK_TOP,
					on ? Ui.withAlpha(UiTheme.ACCENT_DEEP, 0xD0) : hot ? UiTheme.PANEL_TOP : UiTheme.TRACK_BOTTOM);
			Ui.outline(c, anchorX[i], anchorY[i], anchorSize, anchorSize, 4,
					on || hot ? UiTheme.ACCENT : Ui.withAlpha(UiTheme.BORDER, 0x80));
			if (on) {
				Ui.circle(c, anchorX[i] + anchorSize / 2, anchorY[i] + anchorSize / 2, 2, UiTheme.ON_ACCENT);
			}
		}
		drawButton(c, resetSelectedX, resetSelectedY, resetSelectedW, BUTTON_H, "Reset",
				inside(mouseX, mouseY, resetSelectedX, resetSelectedY, resetSelectedW, BUTTON_H), false);
	}

	private void drawButton(UiCanvas c, int x, int y, int w, int h, String label,
			boolean hovered, boolean primary) {
		Ui.surface(c, x, y, w, h, 7,
				primary ? Ui.withAlpha(UiTheme.ACCENT, hovered ? 0x66 : 0x4A)
						: hovered ? UiTheme.PANEL_ALT : UiTheme.TRACK_TOP,
				primary ? Ui.withAlpha(UiTheme.ACCENT_DEEP, 0x52)
						: hovered ? UiTheme.PANEL_TOP : UiTheme.TRACK_BOTTOM);
		Ui.outline(c, x, y, w, h, 7, hovered || primary ? UiTheme.ACCENT : UiTheme.BORDER);
		UiText regular = TYPE.regular();
		Ui.drawCentered(c, regular, label, x + w / 2, Ui.centerTextY(y, h, regular),
				primary ? UiTheme.TEXT : hovered ? UiTheme.TEXT : UiTheme.TEXT_2);
	}

	/** A selected surface gets a bright frame; a hovered one gets a softer one. */
	private void drawHandle(UiCanvas c, int x, int y, int w, int h,
			boolean selected, boolean hovered, boolean dimmed) {
		if (w <= 0 || h <= 0) {
			return;
		}
		int color = selected ? UiTheme.ACCENT
				: hovered ? Ui.withAlpha(UiTheme.ACCENT_2, 0xE0)
				: dimmed ? Ui.withAlpha(UiTheme.BORDER, 0x50) : Ui.withAlpha(UiTheme.ACCENT, 0x88);
		if (selected) {
			Ui.surface(c, x - 2, y - 2, w + 4, h + 4, 9,
					Ui.withAlpha(UiTheme.ACCENT, 0x18), Ui.withAlpha(UiTheme.ACCENT_DEEP, 0x08));
		}
		int arm = Math.min(9, Math.min(w, h) / 2);
		if (arm <= 0) {
			return;
		}
		c.fill(x, y, x + arm, y + 1, color);
		c.fill(x, y, x + 1, y + arm, color);
		c.fill(x + w - arm, y, x + w, y + 1, color);
		c.fill(x + w - 1, y, x + w, y + arm, color);
		c.fill(x, y + h - 1, x + arm, y + h, color);
		c.fill(x, y + h - arm, x + 1, y + h, color);
		c.fill(x + w - arm, y + h - 1, x + w, y + h, color);
		c.fill(x + w - 1, y + h - arm, x + w, y + h, color);
	}

	private static String labelOf(Object target) {
		if (target instanceof HudElementModule hud) {
			return hud.label().isEmpty() ? hud.id() : hud.label();
		}
		if (target instanceof HudPanel panel) {
			return panel.panelLabel();
		}
		return "HUD";
	}

	private float sheen() {
		return (System.currentTimeMillis() % 11000L) / 11000f;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		layoutChrome();
		HudRenderer.prepare(this.width, this.height);
		double mx = event.x();
		double my = event.y();
		int button = event.button();

		if (button == 0 && inside(mx, my, doneX, doneY, doneW, BUTTON_H)) {
			onClose();
			return true;
		}
		if (button == 0 && inside(mx, my, resetAllX, resetAllY, resetAllW, BUTTON_H)) {
			resetAll();
			ClientConfig.saveNow();
			GuiSounds.click();
			return true;
		}
		if (inside(mx, my, toolbarX, toolbarY, toolbarW, TOOLBAR_H)) {
			return true;
		}
		if (inside(mx, my, inspectorX, inspectorY, inspectorW, INSPECTOR_H)) {
			if (selected != null) {
				if (button == 0 && inside(mx, my, resetSelectedX, resetSelectedY, resetSelectedW, BUTTON_H)) {
					reset(selected);
					ClientConfig.saveNow();
					GuiSounds.click();
					return true;
				}
				for (int i = 0; i < 9; i++) {
					if (button == 0 && inside(mx, my, anchorX[i], anchorY[i], anchorSize, anchorSize)) {
						setAnchor(selected, i);
						// Picking a cell means snapping to it, not keeping the old
						// pixel nudge from the previous arrangement.
						moveTo(selected, 0, 0);
						ClientConfig.saveNow();
						GuiSounds.tab();
						return true;
					}
				}
			}
			return true;
		}

		for (int i = HudRenderer.panelCount() - 1; i >= 0; i--) {
			if (!inside(mx, my, HudRenderer.panelX(i), HudRenderer.panelY(i),
					HudRenderer.panelWidth(i), HudRenderer.panelHeight(i))) {
				continue;
			}
			HudPanel panel = HudRenderer.panelAt(i);
			selected = panel;
			if (button == 1) {
				reset(panel);
				ClientConfig.saveNow();
				GuiSounds.click();
				dragging = null;
				return true;
			}
			if (button == 0) {
				startDrag(panel, mx, my, HudRenderer.panelX(i), HudRenderer.panelY(i));
				return true;
			}
			return true;
		}

		for (int i = HudRenderer.slotCount() - 1; i >= 0; i--) {
			if (!inside(mx, my, HudRenderer.slotX(i), HudRenderer.slotY(i),
					HudRenderer.slotWidth(i), HudRenderer.chipHeight())) {
				continue;
			}
			HudElementModule module = HudRenderer.slotModule(i);
			selected = module;
			if (button == 1) {
				reset(module);
				ClientConfig.saveNow();
				GuiSounds.click();
				dragging = null;
				return true;
			}
			if (button == 0) {
				startDrag(module, mx, my, HudRenderer.slotX(i), HudRenderer.slotY(i));
				return true;
			}
			return true;
		}
		selected = null;
		return true;
	}

	private void startDrag(Object target, double mx, double my, int x, int y) {
		GuiSounds.click();
		selected = target;
		dragging = target;
		grabX = (int) Math.round(mx) - x;
		grabY = (int) Math.round(my) - y;
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (dragging == null) {
			return super.mouseDragged(event, dragX, dragY);
		}
		HudRenderer.prepare(this.width, this.height);
		if (baseOf(dragging) == null) {
			dragging = null;
			return true;
		}
		int wantX = (int) Math.round(event.x()) - grabX;
		int wantY = (int) Math.round(event.y()) - grabY;
		int best = anchorOf(dragging);
		long bestCost = Long.MAX_VALUE;
		for (int anchor = 0; anchor < 9; anchor++) {
			setAnchor(dragging, anchor);
			HudRenderer.prepare(this.width, this.height);
			int[] base = baseOf(dragging);
			if (base == null) {
				dragging = null;
				return true;
			}
			long dx = base[0] - wantX;
			long dy = base[1] - wantY;
			long cost = dx * dx + dy * dy;
			if (cost < bestCost) {
				bestCost = cost;
				best = anchor;
			}
		}
		setAnchor(dragging, best);
		HudRenderer.prepare(this.width, this.height);
		int[] base = baseOf(dragging);
		if (base == null) {
			dragging = null;
			return true;
		}
		moveTo(dragging, wantX - base[0], wantY - base[1]);
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragging != null) {
			dragging = null;
			ClientConfig.saveNow();
			return true;
		}
		return super.mouseReleased(event);
	}

	private static int[] baseOf(Object target) {
		if (target instanceof HudElementModule hud) {
			for (int i = 0; i < HudRenderer.slotCount(); i++) {
				if (HudRenderer.slotModule(i) == hud) {
					return new int[]{HudRenderer.slotBaseX(i), HudRenderer.slotBaseY(i),
							HudRenderer.slotWidth(i), HudRenderer.chipHeight()};
				}
			}
			return null;
		}
		if (target instanceof HudPanel panel) {
			for (int i = 0; i < HudRenderer.panelCount(); i++) {
				if (HudRenderer.panelAt(i) == panel) {
					return new int[]{HudRenderer.panelBaseX(i), HudRenderer.panelBaseY(i),
							HudRenderer.panelWidth(i), HudRenderer.panelHeight(i)};
				}
			}
		}
		return null;
	}

	private static int anchorOf(Object target) {
		if (target instanceof HudElementModule hud) {
			return hud.anchor();
		}
		return target instanceof HudPanel panel ? panel.anchor() : 0;
	}

	private static void setAnchor(Object target, int anchor) {
		if (target instanceof HudElementModule hud) {
			hud.setAnchor(anchor);
		} else if (target instanceof HudPanel panel) {
			panel.setAnchor(anchor);
		}
	}

	private static void moveTo(Object target, int x, int y) {
		if (target instanceof HudElementModule hud) {
			hud.moveTo(x, y);
		} else if (target instanceof HudPanel panel) {
			panel.moveTo(x, y);
		}
	}

	private static void reset(Object target) {
		if (target instanceof HudElementModule hud) {
			hud.setAnchor(hud.defaultAnchor());
			hud.resetPosition();
		} else if (target instanceof HudPanel panel) {
			panel.setAnchor(panel.defaultAnchor());
			panel.resetPosition();
		}
	}

	private static void resetAll() {
		for (Module module : ModuleManager.get().modules()) {
			if (module instanceof HudElementModule hud) {
				reset(hud);
			} else if (module instanceof HudPanel panel) {
				reset(panel);
			}
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_TAB) {
			cycleSelection();
			GuiSounds.tab();
			return true;
		}
		return super.keyPressed(event);
	}

	/**
	 * Cycles through every laid-out surface, including the ones sitting under
	 * the toolbar or inspector. Direct clicking is still the primary path; this
	 * is the guarantee that chrome can never make an element unreachable.
	 */
	private void cycleSelection() {
		int panels = HudRenderer.panelCount();
		int chips = HudRenderer.slotCount();
		int total = panels + chips;
		if (total == 0) {
			selected = null;
			return;
		}
		int current = -1;
		if (selected != null) {
			for (int i = 0; i < panels; i++) {
				if (HudRenderer.panelAt(i) == selected) {
					current = i;
					break;
				}
			}
			if (current < 0) {
				for (int i = 0; i < chips; i++) {
					if (HudRenderer.slotModule(i) == selected) {
						current = panels + i;
						break;
					}
				}
			}
		}
		int next = (current + 1) % total;
		selected = next < panels ? HudRenderer.panelAt(next) : HudRenderer.slotModule(next - panels);
	}

	@Override
	public void onClose() {
		ClientConfig.saveNow();
		if (!soundedClose) {
			soundedClose = true;
			GuiSounds.close();
		}
		super.onClose();
		ClientKeybinds.requestInputRestore();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
