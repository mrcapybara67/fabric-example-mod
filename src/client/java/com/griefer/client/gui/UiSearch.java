package com.griefer.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Modern rounded search input: subtle surface, focus ring, icon, placeholder
 * and a thin caret. Keyboard input only flows through it while focused; the
 * owning screen routes charTyped/keyPressed here first.
 */
public class UiSearch {
	private static final int MAX_LEN = 32;

	private final Anim focus = new Anim(0f, 16f);
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

	public void render(GuiGraphics g, Font font, int mouseX, int mouseY, float delta) {
		focus.to(focused ? 1f : 0f, delta);
		boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;

		int bg = Ui.lerpColor(UiTheme.SURFACE, UiTheme.SURFACE_3, Math.max(focus.value(), hovered ? 0.5f : 0f) * 0.6f);
		Ui.roundRect(g, x, y, w, h, UiTheme.RADIUS, bg);
		if (focus.value() > 0.02f) {
			Ui.roundOutline(g, x - 1, y - 1, w + 2, h + 2, UiTheme.RADIUS + 1,
					Ui.lerpColor(0x00000000, UiTheme.ACCENT_18, focus.value()));
		}

		int iconY = y + (h - 10) / 2;
		UiIcons.draw(g, UiIcons.Icon.SEARCH, x + UiTheme.SP_3, iconY, 10,
				focused ? UiTheme.ACCENT : UiTheme.TEXT_3);

		int tx = x + UiTheme.SP_3 + 14;
		int ty = y + (h - font.lineHeight) / 2;
		if (query.isEmpty()) {
			g.drawString(font, "Search modules...", tx, ty, UiTheme.PLACEHOLDER, false);
		} else {
			Ui.drawEllipsized(g, font, query, tx, ty, w - (tx - x) - UiTheme.SP_3, UiTheme.TEXT);
		}

		// Caret (blink, cheap; dies with the screen)
		if (focused && (System.currentTimeMillis() / 530L) % 2L == 0L) {
			int cx = query.isEmpty() ? tx : tx + font.width(query);
			g.fill(cx, ty - 1, cx + 1, ty + font.lineHeight + 1, UiTheme.ACCENT);
		}
	}
}
