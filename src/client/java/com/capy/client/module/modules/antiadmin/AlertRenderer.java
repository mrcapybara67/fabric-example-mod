package com.capy.client.module.modules.antiadmin;

import com.capy.client.gui.Ui;
import com.capy.client.gui.UiFonts;
import com.capy.client.gui.UiIcons;
import com.capy.client.gui.UiTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Draws the Anti Admin alerts.
 *
 * Two presentations share one alert:
 *   <ul>
 *     <li>the guardian style face alert — a large portrait of the player with
 *         a Sakura frame, their name and a warning, fading in and out;</li>
 *     <li>the on-screen notification — a compact card in the corner.</li>
 *   </ul>
 *
 * The face uses the skin the client already has for that player. When the
 * texture is unavailable a neutral silhouette is drawn instead, so the alert
 * still reads correctly.
 */
public final class AlertRenderer {
	private AlertRenderer() {
	}

	/** Draws the guardian style alert in the upper middle of the screen. */
	public static void renderFaceAlert(GuiGraphics g, int screenW, int screenH, AdminAlert alert, float alpha) {
		if (alpha <= 0.01f) {
			return;
		}
		Font display = UiFonts.display();
		Font medium = UiFonts.medium();
		Font regular = UiFonts.regular();

		int face = 84;
		int cardW = 200;
		int cardH = face + 46;
		int cardX = (screenW - cardW) / 2;
		int cardY = Math.max(10, (int) (screenH * 0.16f));

		// A soft vignette that closes in around the screen, like the real
		// guardian effect, but tinted with the Sakura pink.
		int tint = UiTheme.ACCENT_DEEP;
		final int steps = 20;
		final int band = 2;
		for (int i = 0; i < steps; i++) {
			float t = i / (float) steps;
			int a = Math.round(52 * alpha * (1f - t));
			if (a <= 0) {
				continue;
			}
			int color = (a << 24) | (tint & 0x00FFFFFF);
			int inset = i * band;
			g.fill(inset, inset, screenW - inset, inset + band, color);
			g.fill(inset, screenH - inset - band, screenW - inset, screenH - inset, color);
			g.fill(inset, inset, inset + band, screenH - inset, color);
			g.fill(screenW - inset - band, inset, screenW - inset, screenH - inset, color);
		}

		// Card.
		Ui.shadow(g, cardX, cardY, cardW, cardH, UiTheme.RADIUS_LG);
		Ui.surface(g, cardX, cardY, cardW, cardH, UiTheme.RADIUS_LG,
				Ui.withAlpha(UiTheme.PANEL_ALT, Math.round(255 * alpha)),
				Ui.withAlpha(UiTheme.PANEL_TOP, Math.round(255 * alpha)));
		Ui.outline(g, cardX, cardY, cardW, cardH, UiTheme.RADIUS_LG,
				Ui.withAlpha(UiTheme.ACCENT, Math.round(190 * alpha)));

		// Portrait.
		int portraitX = cardX + (cardW - face) / 2;
		int portraitY = cardY + 10;
		drawPortrait(g, alert.skin(), portraitX, portraitY, face, alpha);

		// Warning line.
		int textY = portraitY + face + 8;
		int warnColor = Ui.withAlpha(UiTheme.ACCENT_DEEP, Math.round(255 * alpha));
		UiIcons.draw(g, UiIcons.Icon.ALERT, cardX + 16, textY + 1, 9, warnColor);
		Ui.drawCentered(g, medium, "ADMIN DETECTED", cardX + cardW / 2 + 4, textY, warnColor);

		// Name + distance.
		int nameY = textY + 13;
		Ui.drawCentered(g, display, alert.username(), cardX + cardW / 2, nameY,
				Ui.withAlpha(UiTheme.TEXT, Math.round(255 * alpha)));
		int distY = nameY + display.lineHeight + 1;
		Ui.drawCentered(g, regular, alert.distanceText(), cardX + cardW / 2, distY,
				Ui.withAlpha(UiTheme.TEXT_3, Math.round(255 * alpha)));
	}

	/**
	 * Draws the compact notification card in the top right corner of the HUD.
	 */
	public static void renderNotification(GuiGraphics g, int screenW, int screenH, AdminAlert alert, float alpha) {
		if (alpha <= 0.01f) {
			return;
		}
		Font medium = UiFonts.medium();
		Font regular = UiFonts.regular();

		String title = "ADMIN NEARBY";
		String name = alert.username();
		int w = Math.max(medium.width(title), regular.width(name)) + 46;
		int h = 38;
		int x = screenW - w - 10;
		int y = 10;

		// Slide in slightly as the alert fades in.
		int slide = Math.round((1f - alpha) * 12f);

		Ui.shadow(g, x + slide, y, w, h, UiTheme.RADIUS);
		Ui.surface(g, x + slide, y, w, h, UiTheme.RADIUS,
				Ui.withAlpha(UiTheme.PANEL_ALT, Math.round(255 * alpha)),
				Ui.withAlpha(UiTheme.PANEL_TOP, Math.round(255 * alpha)));
		Ui.outline(g, x + slide, y, w, h, UiTheme.RADIUS,
				Ui.withAlpha(UiTheme.ACCENT, Math.round(160 * alpha)));

		UiIcons.draw(g, UiIcons.Icon.BLOSSOM, x + slide + 10, y + 11, 16,
				Ui.withAlpha(UiTheme.ACCENT, Math.round(255 * alpha)));

		int textX = x + slide + 32;
		Ui.drawEllipsized(g, medium, title, textX, y + 8, w - 42,
				Ui.withAlpha(UiTheme.ACCENT, Math.round(255 * alpha)));
		Ui.drawEllipsized(g, regular, name, textX, y + 20, w - 42,
				Ui.withAlpha(UiTheme.TEXT, Math.round(255 * alpha)));
	}

	/**
	 * Draws the player's head, falling back to a neutral silhouette when the
	 * client has no usable skin for them.
	 */
	private static void drawPortrait(GuiGraphics g, PlayerSkin skin, int x, int y, int size, float alpha) {
		int frame = Ui.withAlpha(UiTheme.ACCENT, Math.round(200 * alpha));
		if (skin != null && skin.body() != null && skin.body().texturePath() != null) {
			try {
				PlayerFaceRenderer.draw(g, skin, x, y, size);
				Ui.outline(g, x - 1, y - 1, size + 2, size + 2, 2, frame);
				return;
			} catch (RuntimeException e) {
				// Fall through to the silhouette: a broken skin texture must not
				// stop the alert from being shown.
			}
		}
		drawSilhouette(g, x, y, size, alpha, frame);
	}

	/** A neutral head-and-shoulders silhouette used when no skin is available. */
	private static void drawSilhouette(GuiGraphics g, int x, int y, int size, float alpha, int frame) {
		int body = Ui.withAlpha(UiTheme.PANEL_ALT, Math.round(255 * alpha));
		int head = Ui.withAlpha(UiTheme.TEXT_3, Math.round(255 * alpha));

		Ui.roundRect(g, x, y, size, size, 2, body);
		int headSize = (int) (size * 0.42f);
		int headX = x + (size - headSize) / 2;
		int headY = y + (int) (size * 0.16f);
		Ui.roundRect(g, headX, headY, headSize, headSize, headSize / 2, head);
		int shoulderW = (int) (size * 0.72f);
		int shoulderH = (int) (size * 0.3f);
		Ui.roundRect(g, x + (size - shoulderW) / 2, y + size - shoulderH - 2, shoulderW, shoulderH, 4, head);
		Ui.outline(g, x - 1, y - 1, size + 2, size + 2, 2, frame);
	}
}
