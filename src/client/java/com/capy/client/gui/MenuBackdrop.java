package com.capy.client.gui;

import com.capy.client.util.Guard;
import java.util.Locale;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * The animated, themed scene behind the title screen.
 *
 * <p>Everything is drawn from primitives - gradients, rows and the client's own
 * vector marks - so the scene resolves against the window every frame instead of
 * stretching a fixed panorama. The same code is crisp at 1080p and at 4K.</p>
 *
 * <p>The opening has three beats: the sky fades up under a short black veil, the
 * crest and wordmark slide into place, and after exactly one second the player's
 * own skin enters from the right edge of the window and settles beside the menu.
 * A menu theme changes the sky, the ridge colours, the light and the moving
 * effect together, so the palettes read as different places rather than as
 * different tints of one place.</p>
 *
 * <p>Every animated quantity is derived from the clock, never accumulated, so a
 * dropped frame cannot make the scene drift. Positions and seeds are computed
 * once into static arrays; the render loop itself allocates nothing.</p>
 */
public final class MenuBackdrop {
	private static final long INTRO_MS = 1600L;
	/** The character is absent for this long, then slides in from the right. */
	private static final long CHARACTER_DELAY_MS = 1000L;
	private static final long CHARACTER_ENTER_MS = 1150L;

	private static final int STARS = 96;
	private static final int MOTES = 54;
	private static final int PETALS = 68;
	private static final int RAIN = 90;
	private static final int BUBBLES = 44;
	private static final int EMBERS = 58;
	private static final int SPARKS = 48;
	private static final int MAX = 96;
	private static final int RIDGE_SAMPLES = 256;

	private static final float[] RX = new float[MAX];
	private static final float[] RY = new float[MAX];
	private static final float[] RS = new float[MAX];
	private static final float[] RP = new float[MAX];
	private static final float[] RW = new float[MAX];
	private static final float[] RA = new float[MAX];
	private static final float[] RIDGE_FAR = new float[RIDGE_SAMPLES];
	private static final float[] RIDGE_NEAR = new float[RIDGE_SAMPLES];

	static {
		for (int i = 0; i < MAX; i++) {
			RX[i] = hash(i * 31 + 7);
			RY[i] = hash(i * 53 + 11);
			RS[i] = hash(i * 71 + 5);
			RP[i] = hash(i * 97 + 3);
			RW[i] = hash(i * 13 + 17);
			RA[i] = hash(i * 29 + 19);
		}
		for (int i = 0; i < RIDGE_SAMPLES; i++) {
			float t = i / (float) (RIDGE_SAMPLES - 1);
			RIDGE_FAR[i] = 0.5f + 0.5f * (float) Math.sin(t * 7.3f + 1.1f);
			RIDGE_NEAR[i] = 0.5f + 0.5f * (float) Math.sin(t * 4.1f + 3.4f);
		}
	}

	private static long openedAt;
	private static Object openedFor;
	private static Supplier<PlayerSkin> skinLookup;
	private static PlayerSkin cachedSkin;
	private static boolean skinFailureReported;

	private MenuBackdrop() {
	}

	/**
	 * Called when a title screen is initialised. A new screen replays the
	 * opening; a resize of the same screen does not.
	 */
	public static void onOpen(Object screen) {
		if (screen != openedFor) {
			openedFor = screen;
			restart();
		}
	}

	/** Replays the opening from black, used when the theme changes. */
	public static void restart() {
		openedAt = System.currentTimeMillis();
		skinLookup = null;
		cachedSkin = null;
		skinFailureReported = false;
	}

	/**
	 * Draws one frame.
	 *
	 * @return true when the backdrop was drawn and the vanilla panorama should
	 *         be cancelled; false leaves vanilla's background in place
	 */
	public static boolean render(GuiGraphics graphics) {
		if (graphics == null || graphics.guiWidth() <= 0 || graphics.guiHeight() <= 0) {
			return false;
		}
		try {
			draw(graphics, MenuTheme.selected());
			return true;
		} catch (Throwable failure) {
			// A failed backdrop must not leave a black menu behind: the caller
			// keeps vanilla's panorama when this returns false.
			Guard.report("menu backdrop", failure);
			return false;
		}
	}

	private static void draw(GuiGraphics g, MenuTheme theme) {
		if (g == null || theme == null) {
			return;
		}
		int w = g.guiWidth();
		int h = g.guiHeight();
		if (w <= 0 || h <= 0) {
			return;
		}
		long now = System.currentTimeMillis();
		if (openedAt == 0L) {
			openedAt = now;
		}
		float intro = clamp((now - openedAt) / (float) INTRO_MS);
		float scale = resolutionScale(g);

		drawSky(g, w, h, theme);
		drawStars(g, w, h, now, theme, scale);
		drawLight(g, w, h, now, theme, scale);
		if (theme.effect() == MenuTheme.Effect.AURORA) {
			drawAurora(g, w, h, now, theme, scale);
		}
		if (theme.effect() == MenuTheme.Effect.RAIN) {
			drawClouds(g, w, h, theme);
		}
		drawRidges(g, w, h, theme);
		drawEffect(g, w, h, now, theme, intro, scale);
		drawCharacter(g, w, h, now, theme, scale);
		drawMark(g, w, h, intro, theme);
		drawVignette(g, w, h);

		int veil = Math.round((1f - easeOut(intro)) * 255f);
		if (veil > 0) {
			g.fill(0, 0, w, h, veil << 24);
		}
	}

	private static void drawSky(GuiGraphics g, int w, int h, MenuTheme theme) {
		g.fillGradient(0, 0, w, h, theme.skyTop(), theme.skyMid());
		g.fillGradient(0, Math.round(h * 0.48f), w, Math.round(h * 0.88f),
				withAlpha(theme.horizon(), 0), withAlpha(theme.horizon(), 78));
		g.fillGradient(0, Math.round(h * 0.66f), w, h,
				0x00000000, 0x72000000);
		// A cool wash from the top keeps the large empty sky from reading flat.
		g.fillGradient(0, 0, w, Math.round(h * 0.45f),
				withAlpha(theme.glow(), 24), 0x00000000);
	}

	/** Stars are fixed points; only their brightness moves. */
	private static void drawStars(GuiGraphics g, int w, int h, long now, MenuTheme theme,
			float scale) {
		if (theme.starAlpha() <= 0.02f) {
			return;
		}
		for (int i = 0; i < STARS; i++) {
			float x = RX[i] * w;
			float y = RY[i] * h * 0.56f;
			float twinkle = 0.5f + 0.5f * (float) Math.sin(now / 1500.0 + i * 1.7);
			int alpha = Math.round(105f * theme.starAlpha() * twinkle);
			if (alpha <= 2) {
				continue;
			}
			int size = Math.max(1, Math.round((i % 11 == 0 ? 2 : 1) * scale));
			g.fill(Math.round(x), Math.round(y), Math.round(x) + size, Math.round(y) + size,
					argb(theme.particleA(), alpha));
		}
	}

	/** Sun or moon, depending on how dark the theme is. */
	private static void drawLight(GuiGraphics g, int w, int h, long now, MenuTheme theme,
			float scale) {
		if (theme.effect() == MenuTheme.Effect.RAIN) {
			return;
		}
		boolean night = theme == MenuTheme.AURORA
				|| theme == MenuTheme.MIDNIGHT
				|| theme == MenuTheme.VOID
				|| theme == MenuTheme.NEON;
		int cx = Math.round(w * (night ? 0.18f : 0.72f));
		int cy = Math.round(h * (night ? 0.22f : 0.60f));
		int base = Math.round(Math.min(w, h) * (night ? 0.030f : 0.055f));
		if (night) {
			fillDisc(g, cx, cy, base + Math.round(2 * scale),
					withAlpha(theme.glow(), 26));
			fillDisc(g, cx, cy, base, withAlpha(theme.particleA(), 235));
			// The crescent is a second disc in the sky colour, offset towards
			// the light. It reads as a moon rather than as a bright dot.
			fillDisc(g, cx + Math.round(base * 0.45f), cy - Math.round(base * 0.25f),
					Math.max(1, base - Math.round(1.5f * scale)), withAlpha(theme.skyTop(), 255));
			return;
		}
		int pulse = Math.round(3f * (float) Math.sin(now / 2400.0));
		for (int layer = 3; layer >= 1; layer--) {
			int r = base + pulse + layer * base / 3;
			fillDisc(g, cx, cy, r, withAlpha(theme.glow(), 9 + layer * 6));
		}
		fillDisc(g, cx, cy, Math.max(3, base / 3), withAlpha(theme.sun(), 190));
	}

	/**
	 * Aurora curtains: four vertical ribbons whose centre line is a sum of two
	 * sines. The gradient inside each column is what makes them look like light
	 * in the air instead of four coloured pipes.
	 */
	private static void drawAurora(GuiGraphics g, int w, int h, long now, MenuTheme theme,
			float scale) {
		int step = Math.max(6, Math.round(w / 260f));
		for (int band = 0; band < 4; band++) {
			int baseY = Math.round(h * (0.20f + 0.075f * band));
			int thickness = Math.round(h * (0.075f - band * 0.008f));
			int color = (band & 1) == 0 ? theme.particleA() : theme.particleB();
			for (int x = 0; x < w; x += step) {
				float t = x / (float) Math.max(1, w);
				float wave = (float) Math.sin(t * 6.2f + now / 2600.0 + band * 1.7f) * 0.055f
						+ (float) Math.sin(t * 13.0f + now / 1700.0 + band) * 0.022f;
				int y = baseY + Math.round(wave * h);
				int alpha = Math.round(14f + 16f * (0.5f + 0.5f
						* (float) Math.sin(t * 9.0f - now / 2100.0 + band)));
				g.fillGradient(x, y, x + step, y + thickness,
						withAlpha(color, alpha), withAlpha(color, 0));
			}
		}
		// A thin bright crest on the first curtain: the detail that reads as an
		// aurora rather than as fog.
		int crestY = Math.round(h * 0.20f);
		for (int x = 0; x < w; x += step) {
			float t = x / (float) Math.max(1, w);
			float wave = (float) Math.sin(t * 6.2f + now / 2600.0) * 0.055f
					+ (float) Math.sin(t * 13.0f + now / 1700.0) * 0.022f;
			int y = crestY + Math.round(wave * h);
			g.fill(x, y, x + step, y + Math.max(1, Math.round(2 * scale)),
					withAlpha(theme.particleA(), 62));
		}
	}

	private static void drawClouds(GuiGraphics g, int w, int h, MenuTheme theme) {
		g.fillGradient(0, Math.round(h * 0.20f), w, Math.round(h * 0.32f),
				withAlpha(theme.particleA(), 20), withAlpha(theme.particleA(), 0));
		g.fillGradient(0, Math.round(h * 0.34f), w, Math.round(h * 0.47f),
				withAlpha(theme.particleB(), 16), withAlpha(theme.particleB(), 0));
		for (int i = 0; i < 7; i++) {
			int y = Math.round(h * (0.18f + i * 0.037f));
			int x = Math.round(((RX[i] * 1.4f - 0.2f) * w));
			int width = Math.round(w * (0.22f + RW[i] * 0.30f));
			g.fillGradient(x, y, x + width, y + Math.round(h * 0.035f),
					withAlpha(theme.particleA(), 12), 0x00000000);
		}
	}

	/** Two ridges, drawn in columns. The lit edge shares the near-ridge pass. */
	private static void drawRidges(GuiGraphics g, int w, int h, MenuTheme theme) {
		int horizon = Math.round(h * 0.70f);
		int step = Math.max(3, Math.round(w / 560f));
		int edge = Math.max(1, Math.round(1.5f * resolutionForHeight(h)));
		for (int x = 0; x < w; x += step) {
			int sample = Math.min(RIDGE_SAMPLES - 1, x * RIDGE_SAMPLES / Math.max(1, w));
			int far = horizon - Math.round(RIDGE_FAR[sample] * h * 0.10f);
			int near = horizon - Math.round(RIDGE_NEAR[sample] * h * 0.16f);
			g.fill(x, far, x + step, h, theme.ridgeFar());
			g.fill(x, near, x + step, h, theme.ridgeNear());
			g.fill(x, near, x + step, near + edge, withAlpha(theme.ridgeLight(), 58));
		}
	}

	private static void drawEffect(GuiGraphics g, int w, int h, long now, MenuTheme theme,
			float intro, float scale) {
		switch (theme.effect()) {
			case AURORA -> drawMotes(g, w, h, now, theme, intro, scale, 28);
			case MOTES -> drawMotes(g, w, h, now, theme, intro, scale, MOTES);
			case STARS -> drawMotes(g, w, h, now, theme, intro, scale, 18);
			case RAIN -> drawRain(g, w, h, now, theme, intro, scale);
			case BUBBLES -> drawBubbles(g, w, h, now, theme, intro, scale);
			case EMBERS -> drawEmbers(g, w, h, now, theme, intro, scale);
			case PETALS -> drawPetals(g, w, h, now, theme, intro, scale);
			case VOID -> {
				drawVoid(g, w, h, theme, scale);
				drawMotes(g, w, h, now, theme, intro, scale, 36);
			}
			case NEON -> drawNeon(g, w, h, now, theme, intro, scale);
			default -> {
			}
		}
	}

	/** Slow pollen / fireflies: a soft dot with a brighter heart. */
	private static void drawMotes(GuiGraphics g, int w, int h, long now, MenuTheme theme,
			float intro, float scale, int count) {
		for (int i = 0; i < count; i++) {
			float x = RX[i] * w + (float) Math.sin(now / 2600.0 + i * 1.3) * 34f * scale;
			float phase = (RY[i] + now / 1000f * (0.010f + RS[i] * 0.016f)) % 1f;
			float y = h - phase * h * 0.90f;
			float flicker = 0.45f + 0.55f * (float) Math.sin(now / 620.0 + i * 2.1);
			int alpha = Math.round((45f + 105f * RA[i]) * flicker * intro);
			if (alpha <= 2) {
				continue;
			}
			int core = Math.max(1, Math.round((1 + (i % 5 == 0 ? 1 : 0)) * scale));
			g.fill(Math.round(x) - core, Math.round(y) - core,
					Math.round(x) + core + 1, Math.round(y) + core + 1,
					withAlpha(theme.particleA(), Math.round(alpha * 0.22f)));
			g.fill(Math.round(x), Math.round(y), Math.round(x) + core,
					Math.round(y) + core, withAlpha(theme.particleA(), alpha));
		}
	}

	private static void drawPetals(GuiGraphics g, int w, int h, long now, MenuTheme theme,
			float intro, float scale) {
		float span = h + 100f;
		for (int i = 0; i < PETALS; i++) {
			float speed = 24f + RS[i] * 50f;
			float phase = (now / 1000f) * (speed / span) + RP[i];
			float y = (phase % 1f) * span - 50f;
			float x = RX[i] * w + (float) Math.sin(now / 900.0 + i * 0.9)
					* (16f + RW[i] * 28f) * scale;
			int alpha = Math.round((80f + RA[i] * 90f) * intro);
			int size = Math.max(2, Math.round((2 + (i % 3 == 0 ? 1 : 0)) * scale));
			int color = (i % 5 == 0) ? theme.particleB() : theme.particleA();
			g.fill(Math.round(x), Math.round(y), Math.round(x) + size + 1,
					Math.round(y) + size, withAlpha(color, alpha));
			g.fill(Math.round(x) + 1, Math.round(y) + size, Math.round(x) + 2,
					Math.round(y) + size + Math.max(1, Math.round(2 * scale)),
					withAlpha(color, Math.max(0, alpha - 45)));
		}
	}

	private static void drawRain(GuiGraphics g, int w, int h, long now, MenuTheme theme,
			float intro, float scale) {
		for (int i = 0; i < RAIN; i++) {
			float speed = 420f + RS[i] * 380f;
			float x = RX[i] * w + (float) Math.sin(now / 4000.0 + i) * 24f * scale;
			float y = ((now / 1000f) * speed + RY[i] * h * 2f) % (h + 80f) - 40f;
			int length = Math.round((10f + RW[i] * 22f) * scale);
			int alpha = Math.round((36f + RA[i] * 50f) * intro);
			int color = withAlpha(theme.particleA(), alpha);
			g.fill(Math.round(x), Math.round(y), Math.round(x) + Math.max(1, Math.round(scale)),
					Math.round(y) + length, color);
			g.fill(Math.round(x) - Math.max(1, Math.round(scale)), Math.round(y) + length / 3,
					Math.round(x), Math.round(y) + length * 2 / 3, color);
		}
	}

	private static void drawBubbles(GuiGraphics g, int w, int h, long now, MenuTheme theme,
			float intro, float scale) {
		UiCanvas c = McCanvas.of(g);
		for (int i = 0; i < BUBBLES; i++) {
			float phase = (RP[i] + now / 1000f * (0.02f + RS[i] * 0.035f)) % 1f;
			int x = Math.round(RX[i] * w + (float) Math.sin(now / 1800.0 + i) * 14f * scale);
			int y = Math.round(h - phase * h * 0.95f);
			int r = Math.max(1, Math.round((1.5f + RA[i] * 2.5f) * scale));
			int alpha = Math.round((30f + RA[i] * 35f) * intro);
			Ui.circle(c, x, y, r, withAlpha(theme.particleA(), alpha));
			Ui.circle(c, x - Math.max(0, r / 2), y - Math.max(0, r / 2), Math.max(1, r / 2),
					withAlpha(theme.particleB(), Math.min(90, alpha + 20)));
		}
	}

	private static void drawEmbers(GuiGraphics g, int w, int h, long now, MenuTheme theme,
			float intro, float scale) {
		for (int i = 0; i < EMBERS; i++) {
			float phase = (RP[i] + now / 1000f * (0.015f + RS[i] * 0.03f)) % 1f;
			float x = RX[i] * w + (float) Math.sin(now / 1000.0 + i * 1.8) * 12f * scale;
			float y = h - phase * h * 0.86f;
			float flicker = 0.4f + 0.6f * (float) Math.sin(now / 240.0 + i * 3.0);
			int alpha = Math.round((40f + 120f * RA[i]) * flicker * intro);
			if (alpha <= 2) {
				continue;
			}
			int size = Math.max(1, Math.round((1 + (i % 4 == 0 ? 1 : 0)) * scale));
			g.fill(Math.round(x) - size, Math.round(y) - size,
					Math.round(x) + size + 1, Math.round(y) + size + 1,
					withAlpha(theme.particleB(), Math.round(alpha * 0.28f)));
			g.fill(Math.round(x), Math.round(y), Math.round(x) + size,
					Math.round(y) + size, withAlpha(theme.particleA(), alpha));
		}
	}

	private static void drawVoid(GuiGraphics g, int w, int h, MenuTheme theme, float scale) {
		int cx = Math.round(w * 0.34f);
		int cy = Math.round(h * 0.57f);
		int r = Math.round(Math.min(w, h) * 0.105f);
		for (int layer = 3; layer >= 1; layer--) {
			fillDisc(g, cx, cy, r + Math.round(layer * 6 * scale),
					withAlpha(theme.glow(), 8 + layer * 5));
		}
		fillDisc(g, cx, cy, r, 0xF0000000);
		UiCanvas c = McCanvas.of(g);
		Ui.ring(c, cx, cy, r + Math.max(2, Math.round(3 * scale)),
				Math.max(1, Math.round(2 * scale)), withAlpha(theme.accent(), 120));
		Ui.ring(c, cx, cy, r + Math.max(4, Math.round(9 * scale)),
				Math.max(1, Math.round(1 * scale)), withAlpha(theme.accent(), 40));
	}

	private static void drawNeon(GuiGraphics g, int w, int h, long now, MenuTheme theme,
			float intro, float scale) {
		int step = Math.max(28, Math.round(w / 16f));
		for (int x = 0; x < w; x += step) {
			g.fillGradient(x, Math.round(h * 0.42f), x + Math.max(1, Math.round(scale)), h,
					withAlpha(theme.accent(), 0), withAlpha(theme.accent(), 24));
		}
		int scan = Math.round((now / 9f) % h);
		g.fillGradient(0, scan - 18, w, scan, 0x00000000, withAlpha(theme.accent(), 22));
		for (int i = 0; i < SPARKS; i++) {
			float x = RX[i] * w;
			float y = RY[i] * h * 0.8f + (float) Math.sin(now / 1400.0 + i) * 8f;
			int alpha = Math.round((35f + RA[i] * 80f) * intro);
			int size = Math.max(1, Math.round(scale));
			g.fill(Math.round(x), Math.round(y), Math.round(x) + size, Math.round(y) + size,
					withAlpha(i % 3 == 0 ? theme.particleB() : theme.particleA(), alpha));
		}
	}

	// ------------------------------------------------------------- character

	/**
	 * The player's own skin, absent for the first second and then sliding in
	 * from the right edge. The skin is resolved once per title screen from the
	 * game profile and drawn as a flat front view, so it needs no world, no
	 * entity and no render pass of its own.
	 */
	private static void drawCharacter(GuiGraphics g, int w, int h, long now, MenuTheme theme,
			float scale) {
		long elapsed = now - openedAt - CHARACTER_DELAY_MS;
		if (elapsed < 0) {
			return;
		}
		float t = clamp(elapsed / (float) CHARACTER_ENTER_MS);
		float ease = easeOutCubic(t);
		int unit = Math.max(2, Math.round(Math.min(h * 0.46f, 330f * scale) / 32f));
		int targetCx = Math.round(w * 0.88f);
		int entryCx = w + 16 * unit;
		int cx = Math.round(entryCx + (targetCx - entryCx) * ease);
		int bob = Math.round((float) Math.sin(now / 850.0) * 2f * scale);
		int top = Math.round(h * 0.82f) - 32 * unit + bob;

		UiCanvas c = McCanvas.of(g);
		Ui.discGlow(c, cx, top + 17 * unit, Math.max(16, 8 * unit),
				withAlpha(theme.accent(), 70), 0.28f);
		// A soft floor shadow: two nested ellipses, cheaper than a real blur and
		// enough to seat the character in the scene.
		fillEllipse(g, cx, Math.round(h * 0.835f) + bob, 11 * unit, Math.max(2, unit));
		Minecraft mc = Minecraft.getInstance();
		PlayerSkin skin = skin(mc);
		if (skin != null) {
			drawSkin(g, skin, cx, top, unit);
		} else {
			drawSilhouette(c, cx, top, unit, theme);
		}
		drawName(g, mc, cx, top, unit, theme, scale);
	}

	private static void drawSkin(GuiGraphics g, PlayerSkin skin, int cx, int top, int unit) {
		if (skin.body() == null || skin.body().texturePath() == null) {
			return;
		}
		Identifier texture = skin.body().texturePath();
		int arm = skin.model() == PlayerModelType.SLIM ? 3 : 4;
		// Head (front) and its hat layer.
		blit(g, texture, cx - 4 * unit, top, 8, 8, 8, 8, unit);
		blit(g, texture, cx - 4 * unit, top, 40, 8, 8, 8, unit);
		// Torso.
		blit(g, texture, cx - 4 * unit, top + 8 * unit, 20, 20, 8, 12, unit);
		// Right arm (viewer's left), left arm (viewer's right).
		blit(g, texture, cx - (4 + arm) * unit, top + 8 * unit, 44, 20, arm, 12, unit);
		blit(g, texture, cx + 4 * unit, top + 8 * unit, 36, 52, arm, 12, unit);
		// Legs.
		blit(g, texture, cx - 4 * unit, top + 20 * unit, 4, 20, 4, 12, unit);
		blit(g, texture, cx, top + 20 * unit, 20, 52, 4, 12, unit);
	}

	private static void blit(GuiGraphics g, Identifier texture, int x, int y, int u, int v,
			int sourceW, int sourceH, int unit) {
		g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v,
				sourceW * unit, sourceH * unit, 64, 64);
	}

	/** A plate-and-initial stand-in when no skin can be resolved yet. */
	private static void drawSilhouette(UiCanvas c, int cx, int top, int unit, MenuTheme theme) {
		int plate = withAlpha(theme.skyMid(), 210);
		Ui.roundRect(c, cx - 8 * unit, top, 16 * unit, 32 * unit, Math.max(4, 2 * unit), plate);
		Ui.roundRect(c, cx - 4 * unit, top + 2 * unit, 8 * unit, 8 * unit,
				Math.max(2, unit), withAlpha(theme.accent(), 180));
		Ui.roundRect(c, cx - 5 * unit, top + 11 * unit, 10 * unit, 20 * unit,
				Math.max(2, unit), withAlpha(theme.accent(), 120));
	}

	private static void drawName(GuiGraphics g, Minecraft mc, int cx, int top, int unit,
			MenuTheme theme, float scale) {
		if (mc == null || mc.font == null) {
			return;
		}
		String name = mc.getUser() == null || mc.getUser().getName() == null
				? "You" : mc.getUser().getName();
		int textW = mc.font.width(name);
		int pad = Math.max(5, Math.round(4 * scale));
		int boxH = mc.font.lineHeight + pad;
		int boxY = top - boxH - Math.max(6, Math.round(5 * scale));
		UiCanvas c = McCanvas.of(g);
		Ui.surface(c, cx - textW / 2 - pad, boxY, textW + pad * 2, boxH, Math.max(3, boxH / 3),
				withAlpha(theme.skyTop(), 205), withAlpha(theme.skyMid(), 225));
		Ui.outline(c, cx - textW / 2 - pad, boxY, textW + pad * 2, boxH,
				Math.max(3, boxH / 3), withAlpha(theme.accent(), 90));
		g.drawCenteredString(mc.font, name, cx, boxY + (boxH - mc.font.lineHeight) / 2,
				theme.accent());
	}

	/** The cached skin of the local account, or null while it is unavailable. */
	private static PlayerSkin skin(Minecraft mc) {
		try {
			if (mc == null || mc.getGameProfile() == null) {
				return null;
			}
			if (skinLookup == null) {
				skinLookup = mc.getSkinManager().createLookup(mc.getGameProfile(), true);
			}
			PlayerSkin value = skinLookup.get();
			if (value != null) {
				cachedSkin = value;
			}
			// Keep the last resolved skin when a later lookup returns empty
			// for a frame: a menu character must not blink out.
			return value != null ? value : cachedSkin;
		} catch (Throwable failure) {
			if (!skinFailureReported) {
				skinFailureReported = true;
				Guard.report("menu character skin", failure);
			}
			return null;
		}
	}

	// --------------------------------------------------------------- branding

	private static void drawMark(GuiGraphics g, int w, int h, float intro, MenuTheme theme) {
		float ease = easeOut(intro);
		int slide = Math.round((1f - ease) * 18f);
		int crest = Math.round(Math.max(40, Math.min(w, h) * 0.055f));
		int cx = w / 2;
		int top = Math.round(h * 0.055f) + slide;
		int alpha = Math.round(255f * ease);
		UiIcons.draw(McCanvas.of(g), UiIcons.Icon.CREST, cx - crest / 2, top, crest,
				withAlpha(theme.particleA(), alpha));

		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.font != null) {
			int textY = top + crest + Math.round(10 + (1f - ease) * 8f);
			g.drawCenteredString(mc.font, "CAPY CLIENT", cx, textY,
					withAlpha(0xFFFFF1F6, alpha));
			g.drawCenteredString(mc.font, "a quieter way to play", cx, textY + 12,
					withAlpha(theme.particleB(), Math.round(alpha * 0.82f)));
			g.drawString(mc.font, theme.label().toUpperCase(Locale.ROOT), 12, h - 18,
					withAlpha(theme.accent(), 150), false);
		}
	}

	private static void drawVignette(GuiGraphics g, int w, int h) {
		int band = Math.max(24, h / 8);
		g.fillGradient(0, 0, w, band, 0x66000000, 0x00000000);
		g.fillGradient(0, h - band, w, h, 0x00000000, 0x66000000);
		int side = Math.max(20, w / 10);
		g.fillGradient(0, 0, side, h, 0x55000000, 0x00000000);
		g.fillGradient(w - side, 0, w, h, 0x00000000, 0x55000000);
	}

	// --------------------------------------------------------------- helpers

	private static void fillDisc(GuiGraphics g, int cx, int cy, int radius, int color) {
		for (int dy = -radius; dy <= radius; dy++) {
			int half = (int) Math.sqrt(Math.max(0, radius * radius - dy * dy));
			g.fill(cx - half, cy + dy, cx + half, cy + dy + 1, color);
		}
	}

	private static void fillEllipse(GuiGraphics g, int cx, int cy, int radiusX, int radiusY) {
		if (radiusX <= 0 || radiusY <= 0) {
			return;
		}
		for (int dy = -radiusY; dy <= radiusY; dy++) {
			float f = dy / (float) radiusY;
			int half = Math.round(radiusX * (float) Math.sqrt(Math.max(0f, 1f - f * f)));
			g.fill(cx - half, cy + dy, cx + half, cy + dy + 1, 0x52000000);
		}
	}

	/** Line and mark thickness scale, so the scene is not lint at 4K. */
	private static float resolutionScale(GuiGraphics g) {
		return Math.max(1f, Math.min(g.guiWidth() / 1920f, g.guiHeight() / 1080f));
	}

	private static float resolutionForHeight(int h) {
		return Math.max(1f, h / 1080f);
	}

	private static int argb(int rgb, int alpha) {
		return withAlpha(rgb, alpha);
	}

	private static int withAlpha(int color, int alpha) {
		int a = Math.max(0, Math.min(255, alpha));
		return (a << 24) | (color & 0x00FFFFFF);
	}

	private static float hash(int seed) {
		int x = seed * 0x9E3779B9;
		x ^= x >>> 16;
		x *= 0x85EBCA6B;
		x ^= x >>> 13;
		return (x & 0x7FFFFFFF) / (float) 0x7FFFFFFF;
	}

	private static float clamp(float value) {
		return Math.max(0f, Math.min(1f, value));
	}

	private static float easeOut(float t) {
		float u = 1f - t;
		return 1f - u * u * u;
	}

	private static float easeOutCubic(float t) {
		float u = 1f - t;
		return 1f - u * u * u;
	}
}
