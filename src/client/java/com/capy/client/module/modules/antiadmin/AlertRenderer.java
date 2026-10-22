package com.capy.client.module.modules.antiadmin;

import com.capy.client.gui.Ui;
import com.capy.client.gui.UiCanvas;
import com.capy.client.gui.UiIcons;
import com.capy.client.gui.UiLabels;
import com.capy.client.gui.UiText;
import com.capy.client.gui.UiTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Draws the Anti Admin alert.
 *
 * The face effect is the elder guardian's curse, reduced to exactly what it
 * needs to be: the screen darkens and the watched player's face drifts down
 * past the view and is gone. No bloom, no ring, no pulse, no petals - the
 * movement is the entire effect.
 *
 * Two details make it read like vanilla rather than like a sliding sticker:
 * <ul>
 *   <li>the travel is eased at both ends, so it enters above the screen,
 *       accelerates gently through the middle and leaves through the bottom
 *       instead of appearing or vanishing mid-screen;</li>
 *   <li>the face grows very slightly as it descends, which is what sells the
 *       "passing close to the camera" feeling.</li>
 * </ul>
 *
 * The identity line travels with the face so the name is always attached to
 * whoever it belongs to. Nothing here takes input away or covers the
 * crosshair for longer than the read takes.
 */
public final class AlertRenderer {
	/** The translated labels, resolved once instead of every frame. */
	private static final String LABEL_WATCHING = UiLabels.translate("capy.alert.watching", "%s is watching you");
	private static final String LABEL_NEARBY = UiLabels.translate("capy.alert.nearby", "WATCHLIST PLAYER NEARBY");

	private AlertRenderer() {
	}

	/**
	 * Draws the guardian style face effect.
	 *
	 * @param graphics the Minecraft graphics handle used for the skin texture;
	 *                 may be null, in which case a neutral silhouette is drawn
	 *                 instead
	 */
	public static void renderFaceAlert(UiCanvas c, UiText regular, UiText display,
			GuiGraphics graphics, int screenW, int screenH, AdminAlert alert) {
		long now = System.currentTimeMillis();
		float alpha = alert.alpha(now);
		if (alpha <= 0.01f) {
			return;
		}
		float travel = alert.travel(now);

		// The screen darkens, the way the guardian effect does.
		int veil = Math.round(150 * alpha);
		if (veil > 0) {
			c.fill(0, 0, screenW, screenH, (veil << 24) | 0x0A0510);
		}

		int base = Math.min(screenW, screenH);
		int faceSize = Math.max(96, Math.min(200, (int) (base * 0.24f)));
		int size = Math.round(faceSize * (1f + 0.06f * travel));

		// Travel from just above the top edge to just below the bottom edge,
		// so the face both appears and disappears off screen instead of
		// popping in and out in the middle of the view.
		int cx = screenW / 2;
		float startY = -size * 0.75f;
		float endY = screenH + size * 0.75f;
		int cy = Math.round(startY + (endY - startY) * travel);

		// Only the identity is faded; the face leaves by moving, which is what
		// makes it look like an animation rather than a fading texture.
		c.pushClip(0, 0, screenW, screenH);
		drawPortrait(c, graphics, alert.skin(), cx - size / 2, cy - size / 2, size);
		c.popClip();

		int revealAlpha = Math.round(255 * alpha * alert.reveal(now));
		if (revealAlpha > 0) {
			// The name sits under the face and travels with it, so it is always
			// attached to the right person.
			String message = LABEL_WATCHING.replace("%s", alert.username());
			int titleY = cy + size / 2 + 10;
			Ui.drawCentered(c, display, message, cx, titleY, Ui.withAlpha(UiTheme.TEXT, revealAlpha));
			Ui.drawCentered(c, regular, alert.distanceText(), cx,
					titleY + display.lineHeight() + 4, Ui.withAlpha(UiTheme.TEXT_2, revealAlpha));
		}
	}

	/**
	 * Draws the compact notification in the top right corner.
	 *
	 * The two lines are centred inside their own row boxes rather than placed
	 * at fixed offsets, so the title and the name line up on the same left
	 * edge whatever the fonts resolve to.
	 */
	public static void renderNotification(UiCanvas c, UiText regular, UiText medium, int screenW, int screenH,
			AdminAlert alert, float alpha) {
		if (alpha <= 0.01f) {
			return;
		}
		int accent = UiTheme.ACCENT;

		String title = LABEL_NEARBY;
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

		int iconSize = 16;
		UiIcons.draw(c, UiIcons.Icon.BLOSSOM, x + 11, y + (h - iconSize) / 2, iconSize,
				Ui.withAlpha(accent, Math.round(240 * alpha)));

		// Two stacked rows inside the panel: the title above, the name below.
		int textX = x + 34;
		int rowH = h / 2;
		Ui.drawEllipsized(c, medium, title, textX, Ui.centerTextY(y, rowH, medium), w - 44,
				Ui.withAlpha(accent, Math.round(240 * alpha)));
		Ui.drawEllipsized(c, regular, name, textX, Ui.centerTextY(y + rowH, rowH, regular), w - 44,
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
	private static void drawPortrait(UiCanvas c, GuiGraphics graphics, PlayerSkin skin, int x, int y, int size) {
		if (skin != null && skin.body() != null && skin.body().texturePath() != null && graphics != null) {
			try {
				PlayerFaceRenderer.draw(graphics, skin, x, y, size);
				return;
			} catch (RuntimeException e) {
				// A broken skin must not stop the alert from being shown.
			}
		}
		drawSilhouette(c, x + size / 2, y + size / 2, size / 2, 1f);
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
