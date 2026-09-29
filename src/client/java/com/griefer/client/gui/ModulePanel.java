package com.griefer.client.gui;

import com.griefer.client.module.Module;
import com.griefer.client.module.Setting;
import com.griefer.client.module.SliderSetting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * One draggable panel in the ClickGUI representing a module.
 *
 * Layout (top to bottom):
 *   [chamfered header]  module name + status LED
 *   [optional]          description line
 *   [optional]          setting rows (slider: label, value, bracket track)
 *   [footer]            bottom edge line
 *
 * Interaction:
 *   Left press + release (no drag) on header -> toggle module
 *   Left drag on header                      -> move panel
 *   Right click on header                    -> expand / collapse settings
 *   Middle click on header                   -> reset all settings
 *   Left press / drag on slider row          -> set value
 */
public class ModulePanel {
	public static final int WIDTH = 128;
	public static final int HEADER = 20;
	public static final int ROW = 16;
	public static final int DESC_H = 10;
	public static final int FOOTER = 2;
	public static final int PADDING = 6;
	public static final int CHAMFER = 6;

	private static final int DRAG_SLOP = 4;

	private final Module module;

	private int x;
	private int y;
	private boolean open;

	// Panel drag state
	private boolean pressingHeader;
	private boolean draggingPanel;
	private boolean movedFar;
	private double pressX;
	private double pressY;
	private int grabOffX;
	private int grabOffY;

	// Slider drag state
	private SliderSetting activeSlider;

	private boolean hovered;

	public ModulePanel(Module module, int x, int y) {
		this.module = module;
		this.x = x;
		this.y = y;
	}

	public Module module() {
		return module;
	}

	public int x() {
		return x;
	}

	public int y() {
		return y;
	}

	public void setPosition(int x, int y) {
		this.x = x;
		this.y = y;
	}

	public int width() {
		return WIDTH;
	}

	public int height(Font font) {
		int h = HEADER + FOOTER;
		if (open) {
			h += DESC_H;
			h += module.settings().size() * ROW;
		}
		return h;
	}

	public void render(GuiGraphics g, Font font, int mouseX, int mouseY, float tickDelta) {
		int w = WIDTH;
		int h = height(font);
		int accent = module.category().accent;
		hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;

		// Drop shadow
		g.fill(x + 2, y + 2, x + w + 2, y + h + 2, Theme.SHADOW);

		// Body with chamfered top-right corner (stepped columns)
		g.fill(x, y, x + w - CHAMFER, y + h, Theme.BG_RAISED);
		for (int i = 0; i < CHAMFER; i++) {
			int cx = x + w - CHAMFER + i;
			int cut = CHAMFER - 1 - i;
			g.fill(cx, y + cut, cx + 1, y + h, Theme.BG_RAISED);
		}

		// Top accent edge: straight run + diagonal stair
		g.fill(x, y, x + w - CHAMFER, y + 1, module.isEnabled() ? accent : Theme.EDGE_HI);
		for (int i = 0; i < CHAMFER; i++) {
			int cx = x + w - CHAMFER + i;
			int cut = CHAMFER - 1 - i;
			g.fill(cx, y + cut, cx + 1, y + cut + 1, module.isEnabled() ? accent : Theme.EDGE_HI);
		}

		// Bottom + left edge
		g.fill(x, y + h - 1, x + w, y + h, hovered ? Theme.EDGE_HI : Theme.EDGE);
		g.fill(x, y, x + 1, y + h, hovered ? Theme.EDGE_HI : Theme.EDGE);

		// Header text + LED
		int tx = x + PADDING;
		int ty = y + (HEADER - 8) / 2;
		g.drawString(font, module.translationKey(), tx, ty, module.isEnabled() ? Theme.TEXT : Theme.TEXT_DIM, false);

		int ledX = x + w - PADDING - 3;
		int ledY = y + (HEADER - 3) / 2;
		if (module.isEnabled()) {
			g.fill(ledX - 1, ledY - 1, ledX + 4, ledY + 4, Theme.LED_GLOW);
		}
		g.fill(ledX, ledY, ledX + 3, ledY + 3, module.isEnabled() ? Theme.GOOD : Theme.TEXT_DISABLED);

		if (module.isEnabled()) {
			g.fill(x + PADDING, y + HEADER - 1, x + w - PADDING, y + HEADER, accent);
		}

		if (!open) {
			return;
		}

		int cy = y + HEADER;

		// Description line
		String desc = Component.translatable(module.descriptionKey()).getString();
		if (!desc.equals(module.descriptionKey()) && font.width(desc) <= WIDTH - PADDING * 2) {
			g.drawString(font, desc, tx, cy, Theme.TEXT_DIM, false);
		}
		cy += DESC_H;

		// Setting rows
		for (Setting<?> s : module.settings()) {
			if (s instanceof SliderSetting slider) {
				renderSlider(g, font, slider, cy);
			}
			cy += ROW;
		}
	}

	private void renderSlider(GuiGraphics g, Font font, SliderSetting slider, int rowY) {
		int accent = module.category().accent;
		int tx = x + PADDING;
		String label = Component.translatable("griefer.module." + module.id() + "." + slider.key()).getString();
		String value = String.valueOf(slider.get());

		g.drawString(font, label, tx, rowY, Theme.TEXT_DIM, false);
		int vw = font.width(value);
		g.drawString(font, value, x + WIDTH - PADDING - vw, rowY, Theme.TEXT, false);

		int trackY = rowY + 11;
		int trackX0 = x + PADDING;
		int trackX1 = x + WIDTH - PADDING;
		g.fill(trackX0, trackY, trackX1, trackY + 1, Theme.EDGE);
		int fillX = trackX0 + (int) (slider.fraction() * (trackX1 - trackX0));
		g.fill(trackX0, trackY, fillX, trackY + 1, accent);
		g.fill(fillX - 1, trackY - 2, fillX + 1, trackY + 3, Theme.TEXT);

		// Tick marks for small integer ranges
		if (slider.max() - slider.min() <= 10) {
			for (int i = slider.min(); i <= slider.max(); i++) {
				int tX = trackX0 + (int) ((i - slider.min()) / (double) (slider.max() - slider.min()) * (trackX1 - trackX0));
				g.fill(tX, trackY + 1, tX + 1, trackY + 3, Theme.EDGE_HI);
			}
		}
	}

	/** Returns true if this panel consumed the press. */
	public boolean mouseClicked(double mouseX, double mouseY, int button, Font font) {
		int h = height(font);
		boolean inside = mouseX >= x && mouseX <= x + WIDTH && mouseY >= y && mouseY <= y + h;
		if (!inside) {
			return false;
		}

		// Slider rows (only when expanded)
		if (open && button == 0) {
			int cy = y + HEADER + DESC_H;
			for (Setting<?> s : module.settings()) {
				if (s instanceof SliderSetting slider && mouseY >= cy + 9 && mouseY <= cy + ROW) {
					activeSlider = slider;
					dragTo(slider, mouseX);
					return true;
				}
				cy += ROW;
			}
		}

		if (mouseY <= y + HEADER) {
			if (button == 0) {
				pressingHeader = true;
				movedFar = false;
				pressX = mouseX;
				pressY = mouseY;
				grabOffX = (int) (mouseX - x);
				grabOffY = (int) (mouseY - y);
			} else if (button == 1) {
				open = !open;
			} else if (button == 2) {
				for (Setting<?> s : module.settings()) {
					s.reset();
				}
			}
			return true;
		}
		return false;
	}

	public void mouseDragged(double mouseX, double mouseY, int button, Font font) {
		if (activeSlider != null) {
			dragTo(activeSlider, mouseX);
			return;
		}
		if (pressingHeader) {
			if (!movedFar && (Math.abs(mouseX - pressX) > DRAG_SLOP || Math.abs(mouseY - pressY) > DRAG_SLOP)) {
				movedFar = true;
				draggingPanel = true;
			}
			if (draggingPanel) {
				this.x = (int) (mouseX - grabOffX);
				this.y = (int) (mouseY - grabOffY);
			}
		}
	}

	/** Returns true if this panel consumed the release (i.e. should toggle). */
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (activeSlider != null) {
			activeSlider = null;
			return false;
		}
		if (pressingHeader) {
			pressingHeader = false;
			boolean wasDrag = draggingPanel;
			draggingPanel = false;
			return !wasDrag && !movedFar && button == 0 && mouseY <= y + HEADER;
		}
		return false;
	}

	private void dragTo(SliderSetting slider, double mouseX) {
		int trackX0 = x + PADDING;
		int trackX1 = x + WIDTH - PADDING;
		slider.setFromFraction((mouseX - trackX0) / (double) (trackX1 - trackX0));
	}
}
