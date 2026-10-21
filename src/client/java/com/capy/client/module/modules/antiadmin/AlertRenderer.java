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
 * The alert imitates the guardian effect of vanilla Minecraft: the screen
 * darkens, a large face appears in the middle and pulses while the message is
 * shown. The only difference is whose face it is - instead of a guardian the
 * player sees the face of the watched account that was detected.
 *
 * The sequence:
 *   <ol>
 *     <li>the screen darkens and a pink light blooms around the centre;</li>
 *     <li>the portrait scales up into the middle of the screen;</li>
 *     <li>the name and the message are revealed;</li>
 *     <li>the face pulses while the alert holds;</li>
 *     <li>everything fades and the portrait shrinks away.</li>
 *   </ol>
 *
 * The alert is a picture only: it never takes the input away and it never
 * blocks the crosshair for longer than it takes to read it.
 *
 * The wording reflects what the client actually observed. A player whose
 * entity the server sent is reported as nearby with a distance, a player the
 * server only lists is reported as online with an unknown position. Neither
 * is called an administrator: the watchlist says which accounts to watch, not
 * what they are.
 */
public final class AlertRenderer {
	/** The translated labels, resolved once instead of every frame. */
	private static final String LABEL_WATCHING = UiLabels.translate("capy.alert.watching", "%s is watching you");
	private static final String LABEL_GOOD_LUCK = UiLabels.translate("capy.alert.good_luck", "good luck, capy user");
	private static final String LABEL_UNKNOWN =
			UiLabels.translate("capy.alert.position_unknown", "online - position unknown");
	private static final String LABEL_NEARBY = UiLabels.translate("capy.alert.nearby", "WATCHLIST PLAYER NEARBY");
	private static final String LABEL_ONLINE = UiLabels.translate("capy.alert.online", "WATCHLIST PLAYER ONLINE");

	private AlertRenderer() {
	}

	/** Horizontal centre of the portrait. */
	public static int portraitCenterX(int screenW) {
		return screenW / 2;
	}

	/** Vertical centre of the portrait, a little above the middle. */
	public static int portraitCenterY(int screenH) {
		return (int) (screenH * 0.42f);
	}

	/**
	 * Draws the guardian style alert.
	 *
	 * @param graphics the Minecraft graphics handle used for the skin texture;
	 *                 may be null, in which case a neutral silhouette is drawn
	 *                 instead
	 */
	public static void renderFaceAlert(UiCanvas c, UiText regular, UiText medium, UiText display,
			GuiGraphics graphics, UiPetals petals, int screenW, int screenH, AdminAlert alert, float alpha) {
		if (alpha <= 0.01f) {
			return;
		}
		long now = System.currentTimeMillis();
		float reveal = alert.revealProgress(now);
		float portrait = alert.portraitProgress(now);
		float pulse = alert.pulse(now);
		boolean nearby = alert.evidence() == Evidence.NEARBY;

		int accent = nearby ? UiTheme.ACCENT : UiTheme.ACCENT_DEEP;

		// Stage 1: the screen darkens, the way the guardian effect does. The
		// veil is one flat tint that breathes, exactly like the vanilla one.
		int veil = Math.round(158 * alpha * (0.92f + 0.08f * pulse));
		if (veil > 0) {
			c.fill(0, 0, screenW, screenH, (veil << 24) | 0x0A0510);
		}

		// The portrait: large, centred and slightly above the middle so it
		// never sits on the crosshair.
		int base = Math.min(screenW, screenH);
		int faceSize = Math.max(110, Math.min(210, (int) (base * 0.26f)));
		int cx = portraitCenterX(screenW);
		int cy = portraitCenterY(screenH);
		int size = Math.max(8, Math.round(faceSize * (0.72f + 0.28f * portrait)));

		// The bloom behind the portrait.
		Ui.halo(c, cx, cy, (int) (size * 0.9f), accent, (0.30f + 0.25f * pulse) * alpha * portrait);

		// The face itself, drawn from the player's own skin.
		drawPortrait(c, graphics, alert.skin(), cx - size / 2, cy - size / 2, size, alpha);

		// A Sakura ring that pulses around the portrait.
		if (portrait > 0.4f) {
			int ring = size / 2 + 6;
			Ui.ring(c, cx, cy, ring, 2, Ui.withAlpha(accent, Math.round(150 * alpha)), 1f);
			Ui.ring(c, cx, cy, ring + 4 + Math.round(3 * pulse), 1,
					Ui.withAlpha(UiTheme.ACCENT_2, Math.round((40 + 60 * pulse) * alpha)), 1f);
		}

		// Stage 3: the identity.
		int revealAlpha = Math.round(255 * alpha * reveal);
		if (revealAlpha > 0) {
			String message = LABEL_WATCHING.replace("%s", alert.username());
			int titleY = cy + size / 2 + 18;
			Ui.drawCentered(c, display, message, cx, titleY, Ui.withAlpha(UiTheme.TEXT, revealAlpha));

			int subY = titleY + display.lineHeight() + 6;
			String sub = LABEL_GOOD_LUCK;
			Ui.drawCentered(c, medium, sub, cx, subY, Ui.withAlpha(accent, revealAlpha));

			// The evidence line: what the client actually knows.
			int infoY = subY + medium.lineHeight() + 8;
			String info = nearby ? alert.distanceText() : LABEL_UNKNOWN;
			Ui.drawCentered(c, regular, info, cx, infoY, Ui.withAlpha(UiTheme.TEXT_2, revealAlpha));
		}

		// The petals that were released when the portrait appeared.
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

		String title = nearby ? LABEL_NEARBY : LABEL_ONLINE;
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
	 * Draws the player's face.
	 *
	 * The face is drawn with Minecraft's own player face renderer, so the UVs
	 * and the texture are exactly the ones the game uses - no stretching and
	 * no second upload. When the client has no usable skin, a neutral
	 * silhouette is drawn instead.
	 */
	private static void drawPortrait(UiCanvas c, GuiGraphics graphics, PlayerSkin skin, int x, int y, int size,
			float alpha) {
		if (skin != null && skin.body() != null && skin.body().texturePath() != null && graphics != null) {
			try {
				PlayerFaceRenderer.draw(graphics, skin, x, y, size);
				return;
			} catch (RuntimeException e) {
				// A broken skin must not stop the alert from being shown.
			}
		}
		drawSilhouette(c, x + size / 2, y + size / 2, size / 2, alpha);
	}

	/** A neutral head and shoulders silhouette used when no skin is available. */
	private static void drawSilhouette(UiCanvas c, int cx, int cy, int r, float alpha) {
		int plate = Ui.withAlpha(UiTheme.PANEL_ALT, Math.round(240 * alpha));
		int silhouette = Ui.withAlpha(UiTheme.TEXT_3, Math.round(235 * alpha));
		Ui.roundRect(c, cx - r, cy - r, r * 2, r * 2, Math.max(4, r / 4), plate);
		int head = Math.max(3, (int) (r * 0.78f));
		Ui.circle(c, cx, cy - (int) (r * 0.24f), head / 2, silhouette);
		// Rounded shoulders.
		int bodyW = Math.max(4, (int) (r * 1.45f));
		int bodyH = Math.max(4, (int) (r * 0.58f));
		Ui.roundRect(c, cx - bodyW / 2, cy + r - bodyH, bodyW, bodyH, Math.max(2, bodyH / 2), silhouette);
	}

}
