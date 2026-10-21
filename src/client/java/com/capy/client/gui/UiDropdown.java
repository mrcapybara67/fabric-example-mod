package com.capy.client.gui;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Dropdown control: a compact selector that opens a list of options.
 *
 * The collapsed control is drawn by the owner in place; the open list is
 * drawn by {@link #renderOverlay} after everything else, so the popup is never
 * clipped by the panel it belongs to.
 */
public class UiDropdown {
	private static final int ROW_H = 16;
	private static final int PAD = 4;

	private final Anim open = new Anim(0f, 18f);
	private List<String> options = List.of();
	private int selected;
	private boolean expanded;
	private boolean upward;

	private int x;
	private int y;
	private int w;
	private int h;

	public void setOptions(List<String> options, int selected) {
		this.options = List.copyOf(options);
		this.selected = Math.max(0, Math.min(this.options.size() - 1, selected));
	}

	public List<String> options() {
		return options;
	}

	public int selected() {
		return selected;
	}

	public String selectedLabel() {
		return selected >= 0 && selected < options.size() ? options.get(selected) : "";
	}

	public boolean isExpanded() {
		return expanded;
	}

	public void close() {
		expanded = false;
	}

	public void bind(int x, int y, int w, int h, boolean upward) {
		this.x = x;
		this.y = y;
		this.w = w;
		this.h = h;
		this.upward = upward;
	}

	/** Height of the open list, including its padding. */
	public int overlayHeight() {
		return options.size() * ROW_H + PAD * 2;
	}

	/** Y coordinate of the open list (above or below the control). */
	private int overlayY() {
		return upward ? y - overlayHeight() : y + h;
	}

	public boolean contains(double mx, double my) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	/** True when the point is inside the open list. */
	public boolean containsOverlay(double mx, double my) {
		if (!expanded) {
			return false;
		}
		int oy = overlayY();
		return mx >= x && mx < x + w && my >= oy && my < oy + overlayHeight();
	}

	/**
	 * Handles a click on the control or on an option. Returns true when the
	 * click was consumed.
	 */
	public boolean mouseClicked(double mx, double my) {
		if (expanded && containsOverlay(mx, my)) {
			int index = (int) ((my - overlayY() - PAD) / (double) ROW_H);
			if (index >= 0 && index < options.size()) {
				selected = index;
				expanded = false;
			}
			return true;
		}
		if (contains(mx, my)) {
			expanded = !expanded;
			return true;
		}
		if (expanded) {
			// Clicking anywhere else dismisses the list.
			expanded = false;
			return true;
		}
		return false;
	}

	/** Draws the collapsed control. */
	public void render(GuiGraphics g, Font font, int mouseX, int mouseY, float delta) {
		open.to(expanded ? 1f : 0f, delta);
		boolean hovered = contains(mouseX, mouseY);
		int r = UiTheme.RADIUS;

		int top = Ui.lerpColor(UiTheme.TRACK_TOP, UiTheme.PANEL_ALT, hovered ? 0.6f : 0f);
		int bottom = Ui.lerpColor(UiTheme.TRACK_BOTTOM, UiTheme.PANEL_TOP, hovered ? 0.6f : 0f);
		Ui.surface(g, x, y, w, h, r, top, bottom);
		Ui.surfaceEdge(g, x, y, w, h, r, 0x2EFFFFFF, 0x12FFFFFF);

		int tx = x + UiTheme.SP_3;
		int ty = y + (h - font.lineHeight) / 2;
		Ui.drawEllipsized(g, font, selectedLabel(), tx, ty, w - UiTheme.SP_3 * 2 - 10, UiTheme.TEXT_2);

		UiIcons.chevron(g, x + w - 14, y + (h - 8) / 2, 8,
				Ui.lerpColor(UiTheme.TEXT_3, UiTheme.ACCENT, open.value()), open.value());
	}

	/** Draws the open list. Called after the rest of the interface. */
	public void renderOverlay(GuiGraphics g, Font font, int mouseX, int mouseY, float delta) {
		open.to(expanded ? 1f : 0f, delta);
		float t = open.value();
		if (t <= 0.02f) {
			return;
		}
		int oy = overlayY();
		int oh = overlayHeight();

		Ui.shadow(g, x, oy, w, oh, UiTheme.RADIUS);
		Ui.surface(g, x, oy, w, oh, UiTheme.RADIUS,
				Ui.withAlpha(UiTheme.PANEL_ALT, Math.round(255 * t)),
				Ui.withAlpha(UiTheme.PANEL_TOP, Math.round(255 * t)));
		Ui.outline(g, x, oy, w, oh, UiTheme.RADIUS, Ui.withAlpha(UiTheme.BORDER, Math.round(255 * t)));

		for (int i = 0; i < options.size(); i++) {
			int rowY = oy + PAD + i * ROW_H;
			boolean hovered = mouseY >= rowY && mouseY < rowY + ROW_H
					&& mouseX >= x && mouseX < x + w;
			if (hovered) {
				Ui.roundRect(g, x + 1, rowY, w - 2, ROW_H, UiTheme.RADIUS,
						Ui.withAlpha(UiTheme.ACCENT, 0x2E));
			}
			int color = i == selected ? UiTheme.ACCENT : (hovered ? UiTheme.TEXT : UiTheme.TEXT_2);
			Ui.drawEllipsized(g, font, options.get(i), x + UiTheme.SP_3, rowY + (ROW_H - font.lineHeight) / 2,
					w - UiTheme.SP_3 * 2 - 12, color);
			if (i == selected) {
				UiIcons.draw(g, UiIcons.Icon.CHECK, x + w - 14, rowY + (ROW_H - 8) / 2, 8, UiTheme.ACCENT);
			}
		}
	}
}
