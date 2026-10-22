package com.capy.client.gui;

import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Single line text field used where the player has to type something (the
 * Anti Admin watchlist). Styled like the search box but with an accent caret
 * and a submit action on Enter.
 *
 * While the field is focused it owns the keyboard: the owning screen must not
 * run its own key handling (or the client's keybind) until the field has
 * released focus.
 */
public class UiTextField {
	private static final int MAX_LEN = 16;

	private final Anim focus = new Anim(0f, 16f);
	private final Anim hover = new Anim(0f, 14f);
	private String text = "";
	private String placeholder = "";
	private boolean focused;
	private Runnable onSubmit;

	private int x;
	private int y;
	private int w;
	private int h;

	public String text() {
		return text;
	}

	public void clear() {
		text = "";
	}

	public boolean isFocused() {
		return focused;
	}

	public void setFocused(boolean focused) {
		this.focused = focused;
	}

	public void setPlaceholder(String placeholder) {
		this.placeholder = placeholder;
	}

	/** Invoked when the player presses Enter with a non-empty field. */
	public void onSubmit(Runnable onSubmit) {
		this.onSubmit = onSubmit;
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
		if (text.length() >= MAX_LEN) {
			return true; // swallow at the limit
		}
		text += event.codepointAsString();
		return true;
	}

	/** Returns true if the key was consumed by the field. */
	public boolean keyPressed(KeyEvent event) {
		if (!focused) {
			return false;
		}
		int key = event.key();
		if (key == GLFW.GLFW_KEY_BACKSPACE) {
			if (!text.isEmpty()) {
				text = text.substring(0, text.length() - 1);
			}
			return true;
		}
		if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
			if (!text.isEmpty() && onSubmit != null) {
				onSubmit.run();
			}
			return true;
		}
		if (key == GLFW.GLFW_KEY_ESCAPE) {
			focused = false;
			return true;
		}
		return false;
	}

	public void render(UiCanvas c, UiText font, int mouseX, int mouseY, float delta) {
		focus.to(focused ? 1f : 0f, delta);
		boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		hover.to(hovered ? 1f : 0f, delta);

		float f = focus.value();
		int r = UiTheme.RADIUS;

		if (f > 0.02f) {
			Ui.roundRect(c, x - 3, y - 3, w + 6, h + 6, r + 3,
					Ui.lerpColor(0x00000000, UiTheme.ACCENT_GLOW, f * 0.9f));
		}

		int top = Ui.lerpColor(UiTheme.TRACK_TOP, UiTheme.PANEL_ALT, Math.max(f, hover.value() * 0.5f));
		int bottom = Ui.lerpColor(UiTheme.TRACK_BOTTOM, UiTheme.PANEL_TOP, Math.max(f, hover.value() * 0.5f));
		Ui.surface(c, x, y, w, h, r, top, bottom);
		Ui.surfaceEdge(c, x, y, w, h, r,
				Ui.lerpColor(0x30FFFFFF, 0x99FFFFFF, f),
				Ui.lerpColor(0x10FFFFFF, 0x30FFFFFF, f));

		int tx = x + UiTheme.SP_3;
		int ty = y + (h - font.lineHeight()) / 2;
		if (text.isEmpty()) {
			font.draw(c, placeholder, tx, ty, UiTheme.PLACEHOLDER);
		} else {
			Ui.drawEllipsized(c, font, text, tx, ty, w - UiTheme.SP_3 * 2, UiTheme.TEXT);
		}

		if (focused && (System.currentTimeMillis() / 530L) % 2L == 0L) {
			// The caret is clamped to the inside of the field: at the character
			// limit the text is ellipsized, so an unclamped caret would be drawn
			// past the border and over the neighbouring control.
			int caret = text.isEmpty() ? 0 : font.width(text);
			int maxCaret = Math.max(0, w - UiTheme.SP_3 * 2);
			int cx = tx + Math.min(caret, maxCaret);
			c.fill(cx, ty - 1, cx + 1, ty + font.lineHeight() + 1, UiTheme.ACCENT);
		}
	}
}
