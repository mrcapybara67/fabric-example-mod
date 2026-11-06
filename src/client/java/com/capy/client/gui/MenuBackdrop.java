package com.capy.client.gui;

import com.capy.client.util.Guard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The animated backdrop behind the title screen.
 *
 * <p>Everything here is drawn with primitives: gradients, one-pixel rows and the
 * client's own vector crest. That is deliberate. A panorama or a background
 * image is tied to one aspect ratio and one texture resolution, while these
 * shapes are resolved against the window every frame, so the same code is sharp
 * on a 1080p laptop and on a 4K display - there is no bitmap to stretch.</p>
 *
 * <p>The opening is staged: the screen starts black, the sky fades up, the
 * crest and the wordmark rise a few pixels into place, and the petals are
 * already drifting while that happens. After that the scene loops forever, so
 * leaving the title screen open never looks frozen.</p>
 */
public final class MenuBackdrop {
	/** Time for the whole opening, in milliseconds. */
	private static final long INTRO_MS = 1600L;
	private static final int PETALS = 64;
	private static final int STARS = 54;

	private static long startedAt;

	private MenuBackdrop() {
	}

	/** Draws one frame. Never throws into the screen's render pass. */
	public static void render(GuiGraphics graphics) {
		try {
			draw(graphics);
		} catch (Throwable failure) {
			Guard.report("menu backdrop", failure);
		}
	}

	private static void draw(GuiGraphics g) {
		if (g == null) {
			return;
		}
		int w = g.guiWidth();
		int h = g.guiHeight();
		if (w <= 0 || h <= 0) {
			return;
		}
		long now = System.currentTimeMillis();
		if (startedAt == 0L) {
			startedAt = now;
		}
		float intro = clamp((now - startedAt) / (float) INTRO_MS);

		// Sky: night at the top, plum in the middle, a warm band at the horizon.
		g.fillGradient(0, 0, w, h, 0xFF120C18, 0xFF2A1426);
		g.fillGradient(0, Math.round(h * 0.42f), w, Math.round(h * 0.78f),
				0x002A1426, 0xCC4A2038);
		g.fillGradient(0, Math.round(h * 0.60f), w, h, 0x884A2038, 0xFF2A1628);

		drawStars(g, w, h, now);
		drawSun(g, w, h, now);
		drawMountains(g, w, h);
		drawPetals(g, w, h, now, intro);
		drawMark(g, w, h, intro);
		drawVignette(g, w, h);

		// The opening itself: a black veil that fades out, so the first frame is
		// a fade rather than a cut.
		int veil = Math.round((1f - easeOut(intro)) * 255f);
		if (veil > 0) {
			g.fill(0, 0, w, h, veil << 24);
		}
	}

	/** Small deterministic star field with a slow twinkle. */
	private static void drawStars(GuiGraphics g, int w, int h, long now) {
		for (int i = 0; i < STARS; i++) {
			float x = hash(i * 37 + 11) * w;
			float y = hash(i * 91 + 5) * h * 0.55f;
			float twinkle = 0.55f + 0.45f * (float) Math.sin(now / 1400.0 + i * 1.7);
			int alpha = Math.round(80f * twinkle);
			if (alpha <= 2) {
				continue;
			}
			int size = Math.max(1, Math.round((i % 9 == 0 ? 2 : 1) * resolutionScale(g)));
			g.fill(Math.round(x), Math.round(y), Math.round(x) + size, Math.round(y) + size,
					argb(0xFFF4E7F2, alpha));
		}
	}

	/** A low, soft sun: a few translucent discs instead of a hard circle. */
	private static void drawSun(GuiGraphics g, int w, int h, long now) {
		int cx = Math.round(w * 0.70f);
		int cy = Math.round(h * 0.62f);
		int base = Math.round(Math.min(w, h) * 0.11f);
		int pulse = Math.round(3f * (float) Math.sin(now / 2400.0));
		for (int layer = 3; layer >= 1; layer--) {
			int r = base + pulse + layer * base / 3;
			int alpha = 10 + layer * 7;
			fillDisc(g, cx, cy, r, argb(0xFFFFC98A, alpha));
		}
		fillDisc(g, cx, cy, Math.max(3, base / 3), argb(0xFFFFE3C2, 170));
	}

	/** Two stepped mountain silhouettes. */
	private static void drawMountains(GuiGraphics g, int w, int h) {
		int horizon = Math.round(h * 0.70f);
		for (int column = 0; column < w; column += 2) {
			float t = column / (float) Math.max(1, w);
			int far = horizon - Math.round((float) (Math.sin(t * 7.3 + 1.1) * 0.5 + 0.5) * h * 0.10f);
			int near = horizon - Math.round((float) (Math.sin(t * 4.1 + 3.4) * 0.5 + 0.5) * h * 0.16f);
			g.fill(column, far, column + 2, h, 0xCC2B1830);
			g.fill(column, near, column + 2, h, 0xE61C1022);
		}
		// A thin lit edge on the near ridge catches the sun.
		for (int column = 0; column < w; column += 2) {
			float t = column / (float) Math.max(1, w);
			int near = horizon - Math.round((float) (Math.sin(t * 4.1 + 3.4) * 0.5 + 0.5) * h * 0.16f);
			g.fill(column, near, column + 2, near + 1, 0x40FFB98A);
		}
	}

	/** The signature drift: petals falling with a slow sway and a little spin. */
	private static void drawPetals(GuiGraphics g, int w, int h, long now, float intro) {
		float span = h + 80f;
		for (int i = 0; i < PETALS; i++) {
			float speed = 26f + hash(i * 13 + 3) * 44f;
			float phase = (now / 1000f) * (speed / span) + hash(i * 7 + 9);
			float y = (phase % 1f) * span - 40f;
			float baseX = hash(i * 29 + 2) * w;
			float sway = (float) Math.sin(now / 900.0 + i * 0.9) * (18f + hash(i) * 26f);
			float x = baseX + sway;
			int alpha = Math.round((90f + hash(i * 3) * 80f) * intro);
			int size = Math.max(2, Math.round((2 + (i % 3 == 0 ? 1 : 0)) * resolutionScale(g)));
			int color = argb(0xFFFFB7D2, alpha);
			g.fill(Math.round(x), Math.round(y), Math.round(x) + size + 1, Math.round(y) + size,
					color);
			// A short tail makes the mark read as a petal rather than a dot.
			g.fill(Math.round(x) + 1, Math.round(y) + size, Math.round(x) + 2,
					Math.round(y) + size + 2, argb(0xFFFFDCE8, Math.max(0, alpha - 40)));
		}
	}

	/** Crest, wordmark and tagline, sliding up into place during the opening. */
	private static void drawMark(GuiGraphics g, int w, int h, float intro) {
		float ease = easeOut(intro);
		int slide = Math.round((1f - ease) * 18f);
		// The brand sits at the top of the frame, clear of the vanilla logo and
		// the button column below it: a custom backdrop should not fight the
		// menu it is behind.
		int crest = Math.round(Math.max(40, Math.min(w, h) * 0.055f));
		int cx = w / 2;
		int top = Math.round(h * 0.055f) + slide;
		int alpha = Math.round(255f * ease);
		UiIcons.draw(McCanvas.of(g), UiIcons.Icon.CREST, cx - crest / 2, top, crest,
				argb(0xFFFCE6F0, alpha));

		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.font != null) {
			int textY = top + crest + Math.round(10 + (1f - ease) * 8f);
			g.drawCenteredString(mc.font, "CAPY CLIENT", cx, textY, argb(0xFFFFF1F6, alpha));
			g.drawCenteredString(mc.font, "a quieter way to play", cx, textY + 12,
					argb(0xFFE3B8CC, Math.round(alpha * 0.75f)));
		}
	}

	/** Darkened edges, so the eye lands on the middle of the screen. */
	private static void drawVignette(GuiGraphics g, int w, int h) {
		int band = Math.max(24, h / 8);
		g.fillGradient(0, 0, w, band, 0x66000000, 0x00000000);
		g.fillGradient(0, h - band, w, h, 0x00000000, 0x66000000);
		int side = Math.max(20, w / 10);
		g.fillGradient(0, 0, side, h, 0x55000000, 0x00000000);
		g.fillGradient(w - side, 0, w, h, 0x00000000, 0x55000000);
	}

	private static void fillDisc(GuiGraphics g, int cx, int cy, int radius, int color) {
		for (int dy = -radius; dy <= radius; dy++) {
			int half = (int) Math.sqrt(Math.max(0, radius * radius - dy * dy));
			g.fill(cx - half, cy + dy, cx + half, cy + dy + 1, color);
		}
	}

	/** Line and mark thickness scale, so the scene is not lint at 4K. */
	private static float resolutionScale(GuiGraphics g) {
		return Math.max(1f, Math.min(g.guiWidth() / 1920f, g.guiHeight() / 1080f));
	}

	private static int argb(int rgb, int alpha) {
		int a = Math.max(0, Math.min(255, alpha));
		return (a << 24) | (rgb & 0x00FFFFFF);
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
}
