package com.capy.client.module.modules.antiadmin;

import com.capy.client.gui.Ui;
import com.capy.client.gui.UiCanvas;
import com.capy.client.gui.UiIcons;
import com.capy.client.gui.UiLabels;
import com.capy.client.gui.UiPetals;
import com.capy.client.gui.UiText;
import com.capy.client.gui.UiTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Draws the Anti Admin alert.
 *
 * The alert is a horizontal "player card" that appears in the upper left of
 * the screen, clear of the crosshair, the hotbar and the chat:
 *
 * <pre>
 *   ( o )  WATCHLIST MATCH
 *          Notch
 *          12.4 blocks away
 * </pre>
 *
 * The sequence is staged:
 *   <ol>
 *     <li>the card fades in and a soft pink halo blooms around it;</li>
 *     <li>the portrait scales up out of the card and the Sakura ring draws
 *         itself around it;</li>
 *     <li>the identity is revealed;</li>
 *     <li>the alert holds with a slow breathing accent;</li>
 *     <li>it leaves again, shrinking slightly.</li>
 *   </ol>
 *
 * The presentation reflects the evidence: a player whose entity the server
 * sent is shown as <i>nearby</i> with a distance, a player the server only
 * lists is shown as <i>online</i> with an unknown position and a quieter
 * accent. The alert never claims that a matched player is an administrator.
 */
public final class AlertRenderer {
	private static final int CARD_W = 268;
	private static final int CARD_H = 80;
	private static final int PORTRAIT = 54;

	/** Where the card sits on the screen. */
	public static final int CARD_X = 16;
	public static final int CARD_Y = 16;
	/** The centre of the portrait, where the blossom burst starts. */
	public static final int PORTRAIT_CX = CARD_X + 14 + PORTRAIT / 2;
	public static final int PORTRAIT_CY = CARD_Y + CARD_H / 2;

	private AlertRenderer() {
	}

	/**
	 * Draws the cinematic alert.
	 *
	 * @param graphics the Minecraft graphics handle used for the skin
	 *                 texture; may be null, in which case a neutral
	 *                 silhouette is drawn instead
	 */
	public static void renderFaceAlert(UiCanvas c, UiText regular, UiText medium, UiText display,
			GuiGraphics graphics, UiPetals petals, int screenW, int screenH, AdminAlert alert, float alpha) {
		if (alpha <= 0.01f) {
			return;
		}
		long now = System.currentTimeMillis();
		float reveal = alert.revealProgress(now);
		float portrait = alert.portraitProgress(now);
		float ring = alert.ringProgress(now);
		float pulse = alert.pulse(now);
		boolean nearby = alert.evidence() == Evidence.NEARBY;

		int accent = nearby ? UiTheme.ACCENT : UiTheme.ACCENT_DEEP;

		// Stage 1: a restrained emphasis behind the alert. The vignette is
		// deliberately faint so it never flashes the whole screen.
		int vignetteAlpha = Math.round(26 * alpha);
		if (vignetteAlpha > 0) {
			for (int i = 0; i < 14; i++) {
				int inset = i * 3;
				int a = Math.round(vignetteAlpha * (1f - i / 14f));
				if (a <= 0) {
					continue;
				}
				int shade = (a << 24) | (UiTheme.ACCENT_DEEP & 0x00FFFFFF);
				c.fill(inset, inset, screenW - inset, inset + 3, shade);
				c.fill(inset, screenH - inset - 3, screenW - inset, screenH - inset, shade);
				c.fill(inset, inset, inset + 3, screenH - inset, shade);
				c.fill(screenW - inset - 3, inset, screenW - inset, screenH - inset, shade);
			}
		}

		// The card drifts in from the left while it fades.
		int slide = Math.round((1f - alpha) * -18f);
		int cardX = CARD_X + slide;
		int cardY = CARD_Y;

		// Stage 1: the halo blooms around the portrait.
		int portraitCx = cardX + 14 + PORTRAIT / 2;
		int portraitCy = cardY + CARD_H / 2;
		int haloRadius = PORTRAIT / 2 + 16;
		Ui.halo(c, portraitCx, portraitCy, haloRadius, accent,
				(0.35f + 0.25f * pulse) * alpha * portrait);

		// The card body.
		int topColor = Ui.withAlpha(UiTheme.PANEL_ALT, Math.round(238 * alpha));
		int bottomColor = Ui.withAlpha(UiTheme.PANEL_TOP, Math.round(238 * alpha));
		Ui.shadow(c, cardX, cardY, CARD_W, CARD_H, UiTheme.RADIUS_LG);
		Ui.surface(c, cardX, cardY, CARD_W, CARD_H, UiTheme.RADIUS_LG, topColor, bottomColor);
		Ui.outline(c, cardX, cardY, CARD_W, CARD_H, UiTheme.RADIUS_LG,
				Ui.withAlpha(accent, Math.round((120 + 60 * pulse) * alpha)));
		Ui.innerBevel(c, cardX, cardY, CARD_W, CARD_H, UiTheme.RADIUS_LG, UiTheme.HIGHLIGHT, UiTheme.SHADE);

		// The accent edge on the leading side of the card.
		Ui.surface(c, cardX + 1, cardY + 14, 2, CARD_H - 28, 1,
				Ui.withAlpha(accent, Math.round(200 * alpha)),
				Ui.withAlpha(accent, Math.round(120 * alpha)));

		// Stage 2: the portrait.
		int size = Math.max(6, Math.round(PORTRAIT * (0.72f + 0.28f * portrait)));
		int px = portraitCx - size / 2;
		int py = portraitCy - size / 2;
		drawPortrait(c, graphics, alert.skin(), px, py, size, portraitCx, portraitCy, size, alpha);

		// Stage 2: the Sakura ring draws itself around the portrait.
		if (ring > 0.02f) {
			int ringRadius = PORTRAIT / 2 + 4;
			Ui.ring(c, portraitCx, portraitCy, ringRadius, 2,
					Ui.withAlpha(accent, Math.round(235 * alpha)), ring);
			if (nearby) {
				// A second, softer ring that breathes while the alert holds.
				Ui.ring(c, portraitCx, portraitCy, ringRadius + 3, 1,
						Ui.withAlpha(UiTheme.ACCENT_2, Math.round((60 + 50 * pulse) * alpha)), 1f);
			}
		}

		// Stage 3: the identity.
		int textX = cardX + 14 + PORTRAIT + 16;
		int textW = CARD_W - (textX - cardX) - 14;
		int labelY = cardY + 16;
		int nameY = labelY + 13;
		int detailY = nameY + 16;

		int revealAlpha = Math.round(255 * alpha * reveal);
		if (revealAlpha > 0) {
			// The label states what the client actually observed.
			String label = UiLabels.translate(nearby ? "capy.alert.match" : "capy.alert.match_online", "");
			UiIcons.draw(c, UiIcons.Icon.ALERT, textX, labelY - 1, 9,
					Ui.withAlpha(accent, revealAlpha));
			Ui.drawEllipsized(c, medium, label, textX + 13, labelY, textW - 13,
					Ui.withAlpha(accent, revealAlpha));

			Ui.drawEllipsized(c, display, alert.username(), textX, nameY, textW,
					Ui.withAlpha(UiTheme.TEXT, revealAlpha));

			String detail = alert.hasDistance()
					? alert.distanceText()
					: UiLabels.translate("capy.alert.position_unknown", "position unknown");
			Ui.drawEllipsized(c, regular, detail, textX, detailY, textW,
					Ui.withAlpha(UiTheme.TEXT_2, Math.round(revealAlpha * 0.9f)));
		}

		// A blossom in the far corner of the card.
		UiIcons.draw(c, UiIcons.Icon.BLOSSOM, cardX + CARD_W - 24, cardY + 12, 13,
				Ui.withAlpha(UiTheme.ACCENT_2, Math.round(150 * alpha * reveal)));

		// The petals that were released when the portrait appeared drift away
		// in front of the card.
		if (petals != null) {
			petals.renderAll(c, alpha);
		}
	}

	/**
	 * Draws the compact notification in the top right corner.
	 */
	public static void renderNotification(UiCanvas c, UiText regular, UiText medium, int screenW, int screenH,
			AdminAlert alert, float alpha) {
		if (alpha <= 0.01f) {
			return;
		}
		boolean nearby = alert.evidence() == Evidence.NEARBY;
		int accent = nearby ? UiTheme.ACCENT : UiTheme.ACCENT_DEEP;

		String title = UiLabels.translate(nearby ? "capy.alert.nearby" : "capy.alert.online", "");
		String name = alert.username();
		int w = Math.max(medium.width(title), regular.width(name)) + 52;
		int h = 42;
		int slide = Math.round((1f - alpha) * 14f);
		int x = screenW - w - 12 - slide;
		int y = 12;

		Ui.shadow(c, x, y, w, h, UiTheme.RADIUS);
		Ui.surface(c, x, y, w, h, UiTheme.RADIUS,
				Ui.withAlpha(UiTheme.PANEL_ALT, Math.round(240 * alpha)),
				Ui.withAlpha(UiTheme.PANEL_TOP, Math.round(240 * alpha)));
		Ui.outline(c, x, y, w, h, UiTheme.RADIUS, Ui.withAlpha(accent, Math.round(170 * alpha)));

		UiIcons.draw(c, UiIcons.Icon.BLOSSOM, x + 11, y + 12, 16, Ui.withAlpha(accent, Math.round(240 * alpha)));

		int textX = x + 34;
		Ui.drawEllipsized(c, medium, title, textX, y + 9, w - 44,
				Ui.withAlpha(accent, Math.round(240 * alpha)));
		Ui.drawEllipsized(c, regular, name, textX, y + 23, w - 44,
				Ui.withAlpha(UiTheme.TEXT, Math.round(240 * alpha)));
	}

	/**
	 * Draws the player's face inside a perfect circle.
	 *
	 * The face is drawn with Minecraft's own player face renderer, so the UVs
	 * and the texture are exactly the ones the game uses. The pixels outside
	 * the circle are then painted with the colour of the card behind it, which
	 * turns the square head into a clean circular portrait without stretching
	 * the texture or uploading anything twice.
	 */
	private static void drawPortrait(UiCanvas c, GuiGraphics graphics, PlayerSkin skin, int x, int y, int size,
			int cx, int cy, int faceSize, float alpha) {
		int r = size / 2;
		if (r <= 0) {
			return;
		}
		// The card colour behind the portrait, used to mask the corners.
		int backdrop = Ui.withAlpha(UiTheme.PANEL_ALT, Math.round(238 * alpha));

		if (skin != null && skin.body() != null && skin.body().texturePath() != null && graphics != null) {
			try {
				PlayerFaceRenderer.draw(graphics, skin, x, y, size);
			} catch (RuntimeException e) {
				// A broken skin must not take the alert down; fall through to
				// the silhouette below.
				drawSilhouette(c, cx, cy, r, alpha, backdrop);
				maskCircle(c, x, y, size, cx, cy, backdrop);
				return;
			}
			maskCircle(c, x, y, size, cx, cy, backdrop);
			// Fade the portrait in with the rest of the alert.
			int fade = Math.round((1f - alpha) * 255);
			if (fade > 0) {
				Ui.circle(c, cx, cy, r, (fade << 24) | (backdrop & 0x00FFFFFF));
			}
		} else {
			drawSilhouette(c, cx, cy, r, alpha, backdrop);
		}
	}

	/** Paints everything outside the portrait circle with the backdrop colour. */
	private static void maskCircle(UiCanvas c, int x, int y, int size, int cx, int cy, int backdrop) {
		int r = size / 2;
		for (int row = 0; row < size; row++) {
			int dy = y + row - cy;
			int half = (int) Math.sqrt(Math.max(0, (double) r * r - (double) dy * dy));
			int inset = r - half;
			if (inset <= 0) {
				continue;
			}
			c.fill(x, y + row, x + inset, y + row + 1, backdrop);
			c.fill(x + size - inset, y + row, x + size, y + row + 1, backdrop);
		}
	}

	/** A neutral head and shoulders silhouette used when no skin is available. */
	private static void drawSilhouette(UiCanvas c, int cx, int cy, int r, float alpha, int backdrop) {
		int head = Math.max(3, (int) (r * 0.86f));
		int body = Math.max(4, (int) (r * 1.5f));
		Ui.circle(c, cx, cy, r, backdrop);
		int silhouette = Ui.withAlpha(UiTheme.TEXT_3, Math.round(235 * alpha));
		Ui.circle(c, cx, cy - (int) (r * 0.24f), head / 2, silhouette);
		c.fill(cx - body / 2, cy + r - (int) (r * 0.62f), cx + body / 2, cy + r, silhouette);
	}
}
