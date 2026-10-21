package com.capy.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Search input in the header: a plum field with a magnifier, a placeholder and
 * a thin accent caret.
 *
 * Keyboard input only flows through the field while it is focused; the owning
 * screen routes charTyped/keyPressed here first and must not run its own
 * shortcuts while the field owns the keyboard.
 */
public class UiSearch {
	private static final int MAX_LEN = 32;

	private final Anim focus = new Anim(0f, 16f);
	private final Anim hover = new Anim(0f, 14f);
	private String query = "";
	private boolean focused;

	// Geometry of the current frame
	private int x;
	private int y;
	private int w;
	private int h;

	public String query() {
		return query;
	}

	public void clear() {
		query = "";
	}

	public boolean isFocused() {
		return focused;
	}

	public void setFocused(boolean focused) {
		this.focused = focused;
	}

	public void bind(int x, int y, int w, int h) {
		this.x = x;
		this.y = y;
		this.w = w;
		this.h = h;
	}

	public boolean contains(double mx, double my) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	public boolean charTyped(CharacterEvent event) {
		if (!focused || !event.isAllowedChatCharacter()) {
			return false;
		}
		if (query.length() >= MAX_LEN) {
			return true; // swallow at the limit
		}
		query += event.codepointAsString();
		return true;
	}

	/** Returns true if the key was consumed. */
	public boolean keyPressed(KeyEvent event) {
		if (!focused) {
			return false;
		}
		int key = event.key();
		if (key == GLFW.GLFW_KEY_BACKSPACE) {
			if (!query.isEmpty()) {
				query = query.substring(0, query.length() - 1);
			}
			return true;
		}
		if (key == GLFW.GLFW_KEY_ESCAPE) {
			focused = false;
			return true;
		}
		return false;
	}

	/** True when the press landed on the clear affordance (only shown with text). */
	public boolean isOnClear(double mx, double my) {
		if (query.isEmpty()) {
			return false;
		}
		int cs = 14;
		int cx = x + w - UiTheme.SP_3 - cs;
		int cy = y + (h - cs) / 2;
		return mx >= cx - 2 && mx < cx + cs + 2 && my >= cy - 2 && my < cy + cs + 2;
	}

	public void render(GuiGraphics g, Font font, int mouseX, int mouseY, float delta) {
		focus.to(focused ? 1f : 0f, delta);
		boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		hover.to(hovered ? 1f : 0f, delta);

		float f = focus.value();
		int r = UiTheme.RADIUS;

		// Focus bloom behind the field.
		if (f > 0.02f) {
			Ui.roundRect(g, x - 3, y - 3, w + 6, h + 6, r + 3,
					Ui.lerpColor(0x00000000, UiTheme.ACCENT_GLOW, f * 0.9f));
		}

		int top = Ui.lerpColor(UiTheme.TRACK_TOP, UiTheme.PANEL_ALT, Math.max(f, hover.value() * 0.5f));
		int bottom = Ui.lerpColor(UiTheme.TRACK_BOTTOM, UiTheme.PANEL_TOP, Math.max(f, hover.value() * 0.5f));
		Ui.surface(g, x, y, w, h, r, top, bottom);
		Ui.surfaceEdge(g, x, y, w, h, r,
				Ui.lerpColor(0x30FFFFFF, 0x99FFFFFF, f),
				Ui.lerpColor(0x10FFFFFF, 0x30FFFFFF, f));

		int iconSize = 12;
		int iconX = x + UiTheme.SP_3;
		int iconY = y + (h - iconSize) / 2;
		UiIcons.draw(g, UiIcons.Icon.SEARCH, iconX, iconY, iconSize,
				Ui.lerpColor(UiTheme.TEXT_3, UiTheme.ACCENT, f));

		int tx = iconX + iconSize + UiTheme.SP_2 + 1;
		int ty = y + (h - font.lineHeight) / 2;
		int textW = w - (tx - x) - UiTheme.SP_3 - (query.isEmpty() ? 0 : 14);
		if (query.isEmpty()) {
			g.drawString(font, "Search modules", tx, ty, UiTheme.PLACEHOLDER, false);
		} else {
			Ui.drawEllipsized(g, font, query, tx, ty, textW, UiTheme.TEXT);
		}

		// Clear button appears once there is something to clear.
		if (!query.isEmpty()) {
			int cs = 14;
			int cx = x + w - UiTheme.SP_3 - cs;
			int cy = y + (h - cs) / 2;
			Ui.surface(g, cx, cy, cs, cs, cs / 2, UiTheme.PANEL_ALT, UiTheme.PANEL_TOP);
			Ui.outline(g, cx, cy, cs, cs, cs / 2, 0x3DFFFFFF);
			UiIcons.draw(g, UiIcons.Icon.CLOSE, cx + 4, cy + 4, 6, UiTheme.TEXT_2);
		}

		// Caret (blink, cheap; dies with the screen).
		if (focused && (System.currentTimeMillis() / 530L) % 2L == 0L) {
			int cx = query.isEmpty() ? tx : tx + font.width(query);
			g.fill(cx, ty - 1, cx + 1, ty + font.lineHeight + 1, UiTheme.ACCENT);
		}
	}
}
