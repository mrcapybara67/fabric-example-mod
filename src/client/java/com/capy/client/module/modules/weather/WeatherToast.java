package com.capy.client.module.modules.weather;

import com.capy.client.gui.Motion;
import com.capy.client.gui.Ui;
import com.capy.client.gui.UiCanvas;
import com.capy.client.gui.UiIcons;
import com.capy.client.gui.UiText;
import com.capy.client.gui.UiTheme;

/**
 * The weather card: a small, self dismissing notice that slides in under the
 * crosshair area and fades away.
 *
 * Why a card and not just chat: a chat line is scrolled away by anything else
 * the server says, and the weather changes while the player is looking at the
 * sky, not at the chat box. The card is drawn with the same surface, accent and
 * type as the rest of the client, so it reads as part of it rather than as a
 * decoration bolted on.
 *
 * The lifetime is wall clock based, so a slow frame cannot freeze the card on
 * screen, and the geometry is measured once per frame from the strings it
 * already holds - nothing is allocated while it is visible.
 */
public final class WeatherToast {
	/** What the sky is doing, which picks the icon and the accent. */
	public enum Kind {
		RAIN,
		THUNDER,
		CLEAR
	}

	/** How long the card stays fully readable. */
	private static final long LIFETIME_MS = 3_400L;
	/** Slide and fade in. */
	private static final float FADE_IN_MS = 240f;
	/** Fade out, a little longer so it leaves softly. */
	private static final float FADE_OUT_MS = 620f;

	private static final int HEIGHT = 46;
	private static final int PAD = 11;
	private static final int ICON = 18;
	private static final int GAP = 8;
	private static final int TOP = 34;
	/** How far the card travels while it fades in. */
	private static final int SLIDE = 12;

	private static String title = "";
	private static String detail = "";
	private static Kind kind = Kind.CLEAR;
	private static long shownAt;
	private static boolean visible;

	private WeatherToast() {
	}

	/**
	 * Shows a card. The wording arrives already translated, so a language
	 * reload mid-card cannot swap the text under the player, and the renderer
	 * itself never touches the resource system.
	 */
	public static void show(Kind kind, String title, String detail) {
		WeatherToast.kind = kind;
		WeatherToast.title = title == null ? "" : title;
		WeatherToast.detail = detail == null ? "" : detail;
		shownAt = System.currentTimeMillis();
		visible = true;
	}

	/** Removes the card immediately, without a fade. */
	public static void hide() {
		visible = false;
	}

	/** 0..1 through the card's life, already eased. */
	public static float alpha(long now) {
		if (!visible) {
			return 0f;
		}
		long age = now - shownAt;
		if (age < 0L || age >= LIFETIME_MS) {
			return 0f;
		}
		float in = age / FADE_IN_MS;
		float out = (LIFETIME_MS - age) / FADE_OUT_MS;
		return Motion.easeOutCubic(Motion.clamp01(Math.min(in, out)));
	}

	/** Draws the card centred near the top of the screen, or nothing. */
	public static void render(UiCanvas c, UiText regular, UiText medium, int screenW) {
		long now = System.currentTimeMillis();
		float alpha = alpha(now);
		if (alpha <= 0.005f) {
			if (visible && now - shownAt >= LIFETIME_MS) {
				visible = false;
			}
			return;
		}
		if (title.isEmpty()) {
			return;
		}

		int textW = Math.max(medium.width(title), regular.width(detail));
		int w = Math.min(screenW - 16, textW + PAD * 2 + ICON + GAP);
		int x = (screenW - w) / 2;
		int y = TOP - Math.round((1f - alpha) * SLIDE);

		int accent = accentFor(kind);
		int a = Math.round(255f * alpha);
		int panelTop = Ui.withAlpha(UiTheme.PANEL_ALT, Math.round(238f * alpha));
		int panelBottom = Ui.withAlpha(UiTheme.PANEL_TOP, Math.round(232f * alpha));

		Ui.shadow(c, x, y, w, HEIGHT, UiTheme.RADIUS);
		Ui.surface(c, x, y, w, HEIGHT, UiTheme.RADIUS, panelTop, panelBottom);
		Ui.outline(c, x, y, w, HEIGHT, UiTheme.RADIUS, Ui.withAlpha(accent, Math.round(150f * alpha)));

		// A short accent bar down the leading edge ties the card to the modules
		// and the HUD chips.
		Ui.surface(c, x + 1, y + 8, 2, HEIGHT - 16, 1,
				Ui.withAlpha(accent, a), Ui.withAlpha(UiTheme.ACCENT_DEEP, a));

		UiIcons.draw(c, iconFor(kind), x + PAD, y + (HEIGHT - ICON) / 2, ICON,
				Ui.withAlpha(accent, Math.round(240f * alpha)));

		int textX = x + PAD + ICON + GAP;
		int textW2 = w - (textX - x) - PAD;
		int rowH = HEIGHT / 2;
		Ui.drawEllipsized(c, medium, title, textX, Ui.centerTextY(y, rowH, medium), textW2,
				Ui.withAlpha(UiTheme.TEXT, a));
		Ui.drawEllipsized(c, regular, detail, textX, Ui.centerTextY(y + rowH, rowH, regular), textW2,
				Ui.withAlpha(accent, Math.round(235f * alpha)));
	}

	private static int accentFor(Kind kind) {
		return switch (kind) {
			case RAIN -> UiTheme.ACCENT_2;
			case THUNDER -> UiTheme.ACCENT;
			case CLEAR -> UiTheme.PALE_CHERRY;
		};
	}

	private static UiIcons.Icon iconFor(Kind kind) {
		return switch (kind) {
			case RAIN -> UiIcons.Icon.RAIN;
			case THUNDER -> UiIcons.Icon.THUNDER;
			case CLEAR -> UiIcons.Icon.SUN;
		};
	}
}
