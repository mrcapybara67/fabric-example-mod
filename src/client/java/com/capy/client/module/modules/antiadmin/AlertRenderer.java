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
 * The face phase copies the elder guardian curse as closely as a flat overlay
 * can. Vanilla spawns a particle that lives for 30 ticks and, on every frame,
 * does this (from {@code ElderGuardianParticleGroup}):
 *
 * <pre>
 *   t     = age / lifetime                       // 0..1 over 1.5 s
 *   alpha = 0.05 + 0.5 * sin(t * PI)             // swell in, peak, fall away
 *   pose.rotate(camera)                          // always faces the player
 *   pose.rotateX(60 - 150 * t)                   // pitches DOWN as it goes
 *   pose.scale(0.4255, -0.4255, -0.4255)
 * </pre>
 *
 * The two ingredients that carry the animation are the opacity swell and the
 * downward pitch. The swell is reproduced; the pitch becomes the portrait
 * falling from above the screen to just over the hotbar, because a flat
 * portrait cannot rotate in 3D. It falls with an accelerating curve and is
 * fully transparent by the time it lands, so it reaches the hotbar and then
 * disappears instead of blinking out in the middle of the screen.
 *
 * There is deliberately no veil, no bloom, no ring and no pulse: vanilla's
 * curse only translucently overlays the face, and adding more would make it
 * something else. Text legibility is handled with a one pixel drop shadow
 * rather than a full screen tint.
 *
 * After the face is gone the wording takes over, in two steps: the watched
 * player's name and then the sign-off.
 */
public final class AlertRenderer {
	/** Resolved once, not every frame. */
	private static final String LABEL_WATCHING = UiLabels.translate("capy.alert.watching", "%s is watching you");
	private static final String LABEL_JOINED = UiLabels.translate("capy.alert.joined", "%s joined the server");
	private static final String LABEL_VANISHED = UiLabels.translate("capy.alert.vanished", "%s has vanished");
	private static final String LABEL_GHOST = UiLabels.translate("capy.alert.ghost", "%s vanished but is still here");
	private static final String LABEL_PROBED = UiLabels.translate("capy.alert.probed", "%s is online and hidden");
	private static final String LABEL_SPECTATOR = UiLabels.translate("capy.alert.spectator", "%s is in spectator");
	private static final String LABEL_HIDDEN_TAG = UiLabels.translate("capy.alert.hidden_tag", "%s hid their nametag");
	private static final String LABEL_SIGN_OFF =
			UiLabels.translate("capy.alert.stay_safe", "stay safe capy client user");
	private static final String LABEL_NEARBY = UiLabels.translate("capy.alert.nearby", "WATCHLIST PLAYER NEARBY");
	private static final String LABEL_JOINED_TITLE =
			UiLabels.translate("capy.alert.joined_title", "WATCHLIST PLAYER JOINED");
	private static final String LABEL_VANISHED_TITLE =
			UiLabels.translate("capy.alert.vanished_title", "WATCHLIST PLAYER VANISHED");
	private static final String LABEL_GHOST_TITLE =
			UiLabels.translate("capy.alert.ghost_title", "WATCHLIST PLAYER STILL HERE");
	private static final String LABEL_PROBED_TITLE =
			UiLabels.translate("capy.alert.probed_title", "WATCHLIST PLAYER ONLINE");
	private static final String LABEL_SPECTATOR_TITLE =
			UiLabels.translate("capy.alert.spectator_title", "WATCHLIST PLAYER IN SPECTATOR");
	private static final String LABEL_HIDDEN_TAG_TITLE =
			UiLabels.translate("capy.alert.hidden_tag_title", "WATCHLIST PLAYER NAMETAG HIDDEN");

	/** Gap between the name line and the sign-off. */
	private static final int LINE_GAP = 10;

	private AlertRenderer() {
	}

	/** Draws the guardian style face effect and then the wording. */
	public static void renderFaceAlert(UiCanvas c, UiText medium, UiText display,
			GuiGraphics graphics, int screenW, int screenH, AdminAlert alert) {
		long now = System.currentTimeMillis();
		if (alert.alpha(now) <= 0.01f) {
			return;
		}
		renderFace(c, graphics, screenW, screenH, alert, now);
		renderWording(c, medium, display, screenW, screenH, alert, now);
	}

	// ------------------------------------------------------------------ face

	private static void renderFace(UiCanvas c, GuiGraphics graphics, int screenW, int screenH,
			AdminAlert alert, long now) {
		float faceAlpha = alert.faceAlpha(now);
		if (faceAlpha <= 0.01f) {
			return;
		}
		float t = alert.faceProgress(now);
		// Falling accelerates, the way the guardian's downward pitch does.
		float fall = (float) Math.pow(t, 1.6);

		int base = Math.min(screenW, screenH);
		int size = Math.max(96, Math.min(260, Math.round(base * 0.42f)));
		// Vanilla's head shrinks a little as it pitches away.
		int drawSize = Math.round(size * (1f - 0.10f * t));

		// The portrait starts above the screen and lands just over the hotbar,
		// which occupies the bottom 22 pixels. The last visible frame therefore
		// sits right on the hotbar, and the phase ends with the face gone.
		int hotbarTop = screenH - 22;
		int startCenterY = Math.round(-size * 0.40f);
		int endCenterY = hotbarTop - 18 - drawSize / 2;
		int centerY = startCenterY + Math.round((endCenterY - startCenterY) * fall);

		int x = screenW / 2 - drawSize / 2;
		int y = centerY - drawSize / 2;

		int tint = Ui.withAlpha(0xFFFFFF, Math.round(255f * faceAlpha));
		drawPortrait(c, graphics, alert.skin(), x, y, drawSize, tint, faceAlpha);
	}

	// --------------------------------------------------------------- wording

	private static void renderWording(UiCanvas c, UiText medium, UiText display,
			int screenW, int screenH, AdminAlert alert, long now) {
		float nameAlpha = alert.nameAlpha(now);
		float signOffAlpha = alert.signOffAlpha(now);
		if (nameAlpha <= 0.01f && signOffAlpha <= 0.01f) {
			return;
		}

		// The block is centred where the face was, so the eye stays in one
		// place through the whole sequence.
		int blockH = display.lineHeight() + LINE_GAP + medium.lineHeight();
		int top = Math.round(screenH * 0.44f) - blockH / 2;
		int maxWidth = Math.max(40, screenW - 48);

		if (nameAlpha > 0.01f) {
			String pattern = switch (alert.kind()) {
				case JOINED -> LABEL_JOINED;
				case VANISHED -> LABEL_VANISHED;
				case GHOST -> LABEL_GHOST;
				case PROBED -> LABEL_PROBED;
				case SPECTATOR -> LABEL_SPECTATOR;
				case HIDDEN_TAG -> LABEL_HIDDEN_TAG;
				case NEARBY -> LABEL_WATCHING;
			};
			String line = pattern.replace("%s", alert.username());
			drawShadowedCentered(c, display, line, screenW / 2, top, maxWidth,
					Ui.withAlpha(UiTheme.TEXT, Math.round(255f * nameAlpha)));
		}
		if (signOffAlpha > 0.01f) {
			drawShadowedCentered(c, medium, LABEL_SIGN_OFF, screenW / 2, top + display.lineHeight() + LINE_GAP,
					maxWidth, Ui.withAlpha(UiTheme.ACCENT, Math.round(255f * signOffAlpha)));
		}
	}

	/**
	 * Centres a line, shortening it with an ellipsis when it does not fit, and
	 * draws a one pixel shadow so it stays readable over any world.
	 *
	 * The shadow inherits the text's alpha, so it never keeps a hard edge on
	 * screen after the line has faded.
	 */
	private static void drawShadowedCentered(UiCanvas c, UiText font, String text, int centerX, int y,
			int maxWidth, int color) {
		if (text == null || text.isEmpty()) {
			return;
		}
		String fitted = fit(font, text, maxWidth);
		int x = centerX - font.width(fitted) / 2;
		int alpha = (color >>> 24) & 0xFF;
		if (alpha <= 0) {
			return;
		}
		int shadow = Ui.withAlpha(0x000000, Math.round(alpha * 0.55f));
		font.draw(c, fitted, x + 1, y + 1, shadow);
		font.draw(c, fitted, x, y, color);
	}

	/** Trims a line to fit, adding an ellipsis. */
	private static String fit(UiText font, String text, int maxWidth) {
		if (maxWidth <= 0 || font.width(text) <= maxWidth) {
			return text;
		}
		String ellipsis = "…";
		String out = text;
		while (out.length() > 1 && font.width(out + ellipsis) > maxWidth) {
			out = out.substring(0, out.length() - 1);
		}
		return out + ellipsis;
	}

	// ---------------------------------------------------------- notification

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

		String title = switch (alert.kind()) {
			case NEARBY -> LABEL_NEARBY;
			case JOINED -> LABEL_JOINED_TITLE;
			case VANISHED -> LABEL_VANISHED_TITLE;
			case GHOST -> LABEL_GHOST_TITLE;
			case PROBED -> LABEL_PROBED_TITLE;
			case SPECTATOR -> LABEL_SPECTATOR_TITLE;
			case HIDDEN_TAG -> LABEL_HIDDEN_TAG_TITLE;
		};
		// A join and the two tab tells carry no position at all; a vanish or a
		// ghost does, because the position was remembered before they went.
		String detail = alert.detailText();
		String name = detail.isEmpty() ? alert.username() : alert.username() + "  ·  " + detail;
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

		int textX = x + 34;
		int rowH = h / 2;
		Ui.drawEllipsized(c, medium, title, textX, Ui.centerTextY(y, rowH, medium), w - 44,
				Ui.withAlpha(accent, Math.round(240 * alpha)));
		Ui.drawEllipsized(c, regular, name, textX, Ui.centerTextY(y + rowH, rowH, regular), w - 44,
				Ui.withAlpha(UiTheme.TEXT, Math.round(240 * alpha)));
	}

	// --------------------------------------------------------------- portrait

	/**
	 * Draws the player's face.
	 *
	 * Minecraft's own player face renderer is used, so the UVs and the texture
	 * are exactly the ones the game uses. Its colour argument is an ARGB tint,
	 * which is how the face fades - a plain blit would pop in and out.
	 *
	 * When the client has no usable skin, a neutral silhouette is drawn in the
	 * same place and fades the same way.
	 */
	private static void drawPortrait(UiCanvas c, GuiGraphics graphics, PlayerSkin skin, int x, int y, int size,
			int tint, float alpha) {
		if (skin != null && skin.body() != null && skin.body().texturePath() != null && graphics != null) {
			try {
				PlayerFaceRenderer.draw(graphics, skin, x, y, size, tint);
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
