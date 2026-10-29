package com.capy.client.module.modules;

import com.capy.client.config.ConfigValues;
import com.capy.client.mixin.FogRendererAccessor;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.Category;
import com.capy.client.module.ChoiceSetting;
import com.capy.client.module.Configurable;
import com.capy.client.module.Module;
import com.capy.client.module.SliderSetting;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Random;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

/**
 * Custom Sky — replaces the sky with one of ten animated themes.
 *
 * <h2>Where it draws</h2>
 * The themes are drawn as one flat painting hung in front of the camera. Each
 * theme works in screen pixels and {@link Canvas} converts them: the viewport is
 * mapped onto a plane at the far end of the view frustum, one unit per pixel,
 * scaled by the field of view the frame was actually projected with. Two
 * consequences are worth knowing about:
 *
 *   <ul>
 *   <li>the plane carries the projection's far depth with the depth test on, so
 *       the painting lands only on pixels the world did not cover. Terrain,
 *       entities and clouds hide it exactly as they hide the vanilla sky, and a
 *       theme is never drawn over a mountain;</li>
 *   <li>every layer is a quad at the same depth, and the depth test passes on
 *       equality, so the layers blend in drawing order like a stack of
 *       transparencies. Darkening, glows and the eclipse's final veil all depend
 *       on that order, not on depth.</li>
 *   </ul>
 *
 * The hook is {@code AFTER_ENTITIES}, not the start of the frame: in 1.21.11 the
 * world is drawn by a frame graph that is built first and executed afterwards,
 * so geometry submitted before the graph runs has no pass to land in. Drawing
 * after the entities is inside the pass, which is why the overlays already use
 * it.
 *
 * The vanilla sky is covered rather than cancelled. Its pass writes no depth, so
 * the painting lands on top of it, and a translucent theme blends over it — which
 * is what the opacity slider is for.
 */
public class CustomSkyModule extends Module implements Configurable {
	private static final int VANILLA = 0;
	private static final int BLACK_HOLE = 1;
	private static final int SPACE = 2;
	private static final int AURORA = 3;
	private static final int SUNSET = 4;
	private static final int DEEP_OCEAN = 5;
	private static final int CHERRY = 6;
	private static final int VAPORWAVE = 7;
	private static final int VOID = 8;
	private static final int ECLIPSE = 9;

	private static final int BUFFER_BYTES = 512 * 1024;
	/**
	 * The phase the painting is drawn in, before every other overlay.
	 *
	 * The ESP and the bracket overlays draw without writing depth, so whichever
	 * of them ran last would be erased by an opaque sky. Ordering the sky into
	 * its own earlier phase makes the result independent of the order in which
	 * the modules happen to be switched on.
	 */
	private static final Identifier SKY_PHASE = Identifier.fromNamespaceAndPath("capy", "sky");

	private final ChoiceSetting theme = setting(new ChoiceSetting("theme", VANILLA,
			"vanilla", "black_hole", "space", "aurora", "sunset_glow",
			"deep_ocean", "cherry_blossom", "vaporwave", "void", "eclipse"));
	private final SliderSetting speed = setting(new SliderSetting("speed", 10, 0, 30, 1));
	private final SliderSetting opacity = setting(new SliderSetting("opacity", 100, 0, 100, 2));
	private final BooleanSetting stars = setting(new BooleanSetting("stars", true));
	private final BooleanSetting fog = setting(new BooleanSetting("fog", false));

	private final Random random = new Random();
	private boolean listening;
	/** Animation clock in ticks, already multiplied by the speed slider. */
	private float time;
	/** Rotation of the black hole's accretion disc, in radians. */
	private float spin;
	private long lastNanos;
	/** Clock value at which the next shooting star is allowed to appear. */
	private float nextShootingStar;
	private float shootingStarAt = -1f;
	private float shootingStarX;
	private float shootingStarY;
	/** Fog as it was before the module switched it off. */
	private boolean fogWasEnabled = true;

	private MultiBufferSource.BufferSource batch;
	private ByteBufferBuilder buffer;

	public CustomSkyModule() {
		super(Category.VISUAL, "custom_sky");
		WorldRenderEvents.AFTER_ENTITIES.addPhaseOrdering(SKY_PHASE, Event.DEFAULT_PHASE);
	}

	@Override
	protected void onToggle(boolean state) {
		if (state) {
			if (!listening) {
				WorldRenderEvents.AFTER_ENTITIES.register(SKY_PHASE, this::render);
				listening = true;
			}
			fogWasEnabled = FogRendererAccessor.capy$fogEnabled();
			lastNanos = 0L;
			return;
		}
		// The event API has no removal, so the listener stays registered and
		// returns immediately while the module is off; what has to be undone is
		// the fog switch and the buffers.
		FogRendererAccessor.capy$setFogEnabled(fogWasEnabled);
		lastNanos = 0L;
		dropBatch();
	}

	// ---------------------------------------------------------------- render

	private void render(WorldRenderContext context) {
		if (!isEnabled()) {
			return;
		}
		applyFog();
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return;
		}
		int index = theme.index();
		if (index == VANILLA) {
			return;
		}

		long now = System.nanoTime();
		float dt = lastNanos == 0L ? 1f / 60f : (now - lastNanos) / 1.0e9f;
		lastNanos = now;
		dt = Math.max(0f, Math.min(0.1f, dt));
		float speedValue = (float) speed.real();
		time += dt * 20f * speedValue;
		spin += dt * 15f;

		float w = mc.getWindow().getGuiScaledWidth();
		float h = mc.getWindow().getGuiScaledHeight();
		if (w < 8f || h < 8f) {
			return;
		}
		float alpha = (float) opacity.real();

		// Near the far plane of the projection: far enough that no terrain can
		// be in front of it, close enough that the depth value still has room
		// below the cleared 1.0.
		float depth = Math.max(16f, mc.gameRenderer.getDepthFar() * 0.9f);
		float fov = CustomFOVModule.lastFov();
		double halfH = depth * Math.tan(Math.toRadians(fov * 0.5));
		double halfW = halfH * (w / (double) h);

		try {
			VertexConsumer vc = batch().getBuffer(RenderTypes.debugQuads());
			Canvas canvas = new Canvas(vc, new PoseStack().last(), w, h, halfW, halfH, depth);
			draw(canvas, index, w, h, alpha);
			batch().endBatch();
		} catch (RuntimeException e) {
			// A broken theme must cost the sky, never the frame.
			dropBatch();
		}
	}

	private void applyFog() {
		boolean wanted = !fog.value() && fogWasEnabled;
		if (FogRendererAccessor.capy$fogEnabled() != wanted) {
			FogRendererAccessor.capy$setFogEnabled(wanted);
		}
	}

	private void draw(Canvas c, int index, float w, float h, float alpha) {
		switch (index) {
			case BLACK_HOLE -> blackHole(c, w, h, alpha);
			case SPACE -> space(c, w, h, alpha);
			case AURORA -> aurora(c, w, h, alpha);
			case SUNSET -> sunset(c, w, h, alpha);
			case DEEP_OCEAN -> deepOcean(c, w, h, alpha);
			case CHERRY -> cherryBlossom(c, w, h, alpha);
			case VAPORWAVE -> vaporwave(c, w, h, alpha);
			case VOID -> voidSky(c, w, h, alpha);
			case ECLIPSE -> eclipse(c, w, h, alpha);
			default -> {
			}
		}
	}

	// ---------------------------------------------------------------- themes

	private void blackHole(Canvas c, float w, float h, float alpha) {
		float cx = w * 0.5f;
		float cy = h * 0.5f;
		c.quad(0f, 0f, w, h, withAlpha(0xFF000000, alpha), withAlpha(0xFF000000, alpha));
		c.glow(cx, cy, 300f, 300f, withAlpha(0xFF6600CC, 0.15f * alpha), 12);

		// Gravitational lensing: eight streaks pulling out of the centre.
		for (int i = 0; i < 8; i++) {
			double angle = i * Math.PI / 4.0;
			float cos = (float) Math.cos(angle);
			float sin = (float) Math.sin(angle);
			c.seg(cx + cos * 40f, cy + sin * 40f, cx + cos * 220f, cy + sin * 220f, 1.5f,
					withAlpha(0xFFFFFFFF, 0.12f * alpha));
		}

		// The accretion disc: white hot inside, embers outside, squashed flat.
		int[] ringColors = {0xFFFFFFFF, 0xFFFFEE88, 0xFFFF9900, 0xFFFF4400, 0xFF880000, 0xFF220000};
		for (int i = 0; i < ringColors.length; i++) {
			float rx = 70f + i * 42f;
			float ry = rx * 0.28f;
			c.ring(cx, cy, rx, ry, 4f, withAlpha(ringColors[i], alpha * (1f - i * 0.12f)), spin + i * 0.4f);
		}
	}

	private void space(Canvas c, float w, float h, float alpha) {
		c.quad(0f, 0f, w, h, withAlpha(0xFF020818, alpha), withAlpha(0xFF000000, alpha));
		nebula(c, w, h, alpha);
		starField(c, w, h, 200, alpha, true);
		shootingStar(c, w, h, alpha);
	}

	private void nebula(Canvas c, float w, float h, float alpha) {
		float[][] spots = {{0.2f, 0.3f}, {0.7f, 0.2f}, {0.5f, 0.6f}};
		int[] colors = {0xFF440088, 0xFF004488, 0xFF008844};
		for (int i = 0; i < spots.length; i++) {
			c.glow(w * spots[i][0], h * spots[i][1], 200f, 200f,
					withAlpha(colors[i], 0.09f * alpha), 10);
		}
	}

	private void aurora(Canvas c, float w, float h, float alpha) {
		c.quad(0f, 0f, w, h, withAlpha(0xFF000000, alpha), withAlpha(0xFF000000, alpha));
		starField(c, w, h, 80, alpha * 0.5f, false);

		int[] colors = {0xFF00FF88, 0xFF00CCDD, 0xFF8833FF};
		float[] baseY = {h * 0.25f, h * 0.38f, h * 0.50f};
		float[] phase = {0f, 2.1f, 4.2f};
		float step = 8f;
		for (int band = 0; band < colors.length; band++) {
			for (float x = 0f; x < w; x += step) {
				float y = baseY[band]
						+ (float) Math.sin(x * 0.012f + time * 0.8f + phase[band]) * 55f;
				int color = colors[band];
				// A vertical strip whose alpha peaks in the middle: two rows of
				// quads, one fading up, one fading down.
				c.quad(x, y - 40f, x + step, y, withAlpha(color, 0f), withAlpha(color, 0.5f * alpha));
				c.quad(x, y, x + step, y + 40f, withAlpha(color, 0.5f * alpha), withAlpha(color, 0f));
			}
		}
	}

	private void sunset(Canvas c, float w, float h, float alpha) {
		c.quad(0f, 0f, w, h * 0.5f, withAlpha(0xFF220044, alpha), withAlpha(0xFF220044, alpha));
		c.quad(0f, h * 0.5f, w, h * 0.8f, withAlpha(0xFFFF66AA, alpha), withAlpha(0xFFFF66AA, alpha));
		c.quad(0f, h * 0.8f, w, h, withAlpha(0xFFFF4400, alpha), withAlpha(0xFFFF4400, alpha));

		float sunX = w * 0.5f;
		float sunY = h * 0.72f;
		for (int i = 0; i < 12; i++) {
			double angle = i * Math.PI / 6.0;
			float cos = (float) Math.cos(angle);
			float sin = (float) Math.sin(angle);
			c.seg(sunX, sunY, sunX + cos * 220f, sunY + sin * 220f, 3f,
					withAlpha(0xFFFF8800, 0.12f * alpha));
		}
		c.glow(sunX, sunY, 130f, 130f, withAlpha(0xFFFF6600, 0.3f * alpha), 14);
		c.disc(sunX, sunY, 55f, 55f, withAlpha(0xFFFFFFCC, alpha));

		// A jagged ridge along the bottom that hides the sun's lower half.
		int points = 20;
		float stepX = w / (points - 1);
		for (int i = 0; i < points - 1; i++) {
			float x0 = i * stepX;
			float x1 = x0 + stepX;
			float y0 = i % 2 == 0 ? h * 0.88f : h * (0.82f + noise(i, 61) * 0.13f);
			float y1 = i % 2 == 0 ? h * (0.82f + noise(i + 1, 61) * 0.13f) : h * 0.88f;
			c.poly(x0, y0, x1, y1, x1, h, x0, h, withAlpha(0xFF000000, alpha));
		}
	}

	private void deepOcean(Canvas c, float w, float h, float alpha) {
		c.quad(0f, 0f, w, h, withAlpha(0xFF001825, alpha), withAlpha(0xFF000000, alpha));

		// Five broad shafts of light, converging as they sink.
		float floorY = h * 0.55f;
		for (int i = 0; i < 5; i++) {
			float topX = w * (0.12f + i * 0.19f);
			float topHalf = 20f + noise(i, 71) * 20f;
			float bottomX = w * 0.5f + (topX - w * 0.5f) * 0.75f;
			c.poly(topX - topHalf, 0f, topX + topHalf, 0f,
					bottomX + 6f, floorY, bottomX - 6f, floorY,
					withAlpha(0xFF0088CC, 0.06f * alpha));
		}

		int[] colors = {0xFF00FFCC, 0xFF00CCFF, 0xFF88FFDD};
		for (int i = 0; i < 60; i++) {
			float speedY = 0.03f + noise(i, 81) * 0.09f;
			float y = h - (noise(i, 82) * h + time * speedY) % h;
			float x = noise(i, 83) * w + (float) Math.sin(time * 1.3f + i) * 0.4f;
			float radius = 1f + noise(i, 84) * 2f;
			int color = colors[i % colors.length];
			c.glow(x, y, radius * 6f, radius * 6f, withAlpha(color, 0.2f * alpha), 4);
			c.disc(x, y, radius, radius, withAlpha(color, 0.85f * alpha));
		}
	}

	private void cherryBlossom(Canvas c, float w, float h, float alpha) {
		c.quad(0f, 0f, w, h, withAlpha(0xFFFFD6E8, alpha), withAlpha(0xFFFFEDD5, alpha));

		float sunX = w * 0.72f;
		float sunY = h * 0.2f;
		c.glow(sunX, sunY, 110f, 110f, withAlpha(0xFFFFBBDD, 0.35f * alpha), 14);
		c.disc(sunX, sunY, 45f, 45f, withAlpha(0xFFFFFFFF, alpha));

		int[] colors = {0xFFFFBBCC, 0xFFFFCCDD, 0xFFFFEEF4};
		for (int i = 0; i < 40; i++) {
			float speedY = 0.05f + noise(i, 91) * 0.1f;
			float y = (noise(i, 92) * h + time * speedY) % h;
			float x = noise(i, 93) * w + (float) Math.sin(time + i * 0.7f) * 1.2f;
			float angle = time * 0.008f + i * 0.7f;
			int color = withAlpha(colors[i % colors.length], (0.7f + noise(i, 94) * 0.2f) * alpha);
			petal(c, x, y, angle, color);
		}
	}

	/** One petal: a 6 by 4 pixel quad turned by {@code angle}. */
	private static void petal(Canvas c, float x, float y, float angle, int argb) {
		float cos = (float) Math.cos(angle);
		float sin = (float) Math.sin(angle);
		float hw = 3f;
		float hh = 2f;
		c.poly(x - hw * cos + hh * sin, y - hw * sin - hh * cos,
				x + hw * cos + hh * sin, y + hw * sin - hh * cos,
				x + hw * cos - hh * sin, y + hw * sin + hh * cos,
				x - hw * cos - hh * sin, y - hw * sin + hh * cos, argb);
	}

	private void vaporwave(Canvas c, float w, float h, float alpha) {
		c.quad(0f, 0f, w, h, withAlpha(0xFFFF006E, alpha), withAlpha(0xFF8B00FF, alpha));

		float sunX = w * 0.5f;
		float sunY = h * 0.38f;
		float radius = 70f;
		c.disc(sunX, sunY, radius, radius, withAlpha(0xFFFF2288, alpha));
		// The stripes are cut out of the disc: only the part of each row that is
		// inside the circle is drawn, so they never spill over its edge.
		for (int i = 0; i < 9; i++) {
			float y = sunY - 35f + i * 8f;
			float dy = y + 1.5f - sunY;
			if (Math.abs(dy) >= radius) {
				continue;
			}
			float half = (float) Math.sqrt(radius * radius - dy * dy);
			c.quad(sunX - half, y, sunX + half, y + 3f,
					withAlpha(0xFF000000, alpha), withAlpha(0xFF000000, alpha));
		}

		// Perspective grid: rows scrolling down towards the viewer, columns
		// fanning out from the vanishing point.
		float vpX = w * 0.5f;
		float vpY = h * 0.65f;
		float scroll = time * 30f % 40f;
		for (int i = 0; i < 8; i++) {
			float y = vpY + (i + 1) * 40f - scroll;
			if (y <= vpY || y >= h) {
				continue;
			}
			c.seg(0f, y, w, y, 1f, withAlpha(0xFF00FFFF, 0.5f * alpha));
		}
		for (int i = 0; i < 10; i++) {
			float bx = vpX + (i - 4.5f) * w * 0.12f;
			c.seg(vpX, vpY, bx, h, 1f, withAlpha(0xFF00FFFF, 0.4f * alpha));
		}

		for (float y = 0f; y < h; y += 4f) {
			c.seg(0f, y, w, y, 1f, withAlpha(0xFF000000, 0.10f * alpha));
		}
	}

	private void voidSky(Canvas c, float w, float h, float alpha) {
		c.quad(0f, 0f, w, h, withAlpha(0xFF000000, alpha), withAlpha(0xFF000000, alpha));

		// Vignette: bands of black stepping inwards, each adding its own share
		// of alpha, so the centre stays clear and the corners go dark.
		float cx = w * 0.5f;
		float cy = h * 0.5f;
		float maxR = (float) Math.sqrt(w * w + h * h) * 0.3f;
		float band = maxR / 14f;
		float previous = 0f;
		for (int i = 1; i <= 14; i++) {
			float t = i / 14f;
			float share = (float) Math.pow(t, 2.2) - previous;
			previous = t;
			c.ring(cx, cy, maxR * t, maxR * t, band + 1f, withAlpha(0xFF000000, 0.53f * share * alpha), 0f);
		}

		// Pixel flicker: a one frame spark, so nothing has to be kept between
		// frames. Up to three can share a frame.
		float chance = (float) Math.min(1.0, 0.04 * speed.real() * 4.0);
		for (int i = 0; i < 3; i++) {
			if (random.nextFloat() < chance) {
				float x = random.nextFloat() * w;
				float y = random.nextFloat() * h;
				c.quad(x, y, x + 1f, y + 1f,
						withAlpha(0xFFFFFFFF, alpha), withAlpha(0xFFFFFFFF, alpha));
			}
		}
	}

	private void eclipse(Canvas c, float w, float h, float alpha) {
		c.quad(0f, 0f, w, h, withAlpha(0xFF111111, alpha), withAlpha(0xFF000000, alpha));

		float mx = w * 0.5f + 60f;
		float my = h * 0.3f;
		for (int i = 0; i < 16; i++) {
			double angle = i * Math.PI / 8.0;
			float cos = (float) Math.cos(angle);
			float sin = (float) Math.sin(angle);
			boolean longRay = i % 2 == 0;
			float length = longRay ? 140f : 90f;
			c.seg(mx + cos * 68f, my + sin * 68f, mx + cos * (68f + length), my + sin * (68f + length),
					2f, withAlpha(0xFFFFFFCC, (longRay ? 0.12f : 0.07f) * alpha));
		}
		c.ring(mx, my, 68f, 68f, 1.5f, withAlpha(0xFF555555, alpha), 0f);
		c.disc(mx, my, 65f, 65f, withAlpha(0xFF282828, alpha));
		c.quad(0f, 0f, w, h, withAlpha(0x44000000, alpha), withAlpha(0x44000000, alpha));
	}

	// ------------------------------------------------------------- star layer

	private void starField(Canvas c, float w, float h, int count, float alpha, boolean drift) {
		if (!stars.value()) {
			return;
		}
		for (int i = 0; i < count; i++) {
			float x = noise(i, 101) * w;
			float baseY = noise(i, 102) * h;
			float velocity = 0.02f + noise(i, 103) * 0.06f;
			float y = drift ? (baseY + time * velocity) % h : baseY;
			float size = 0.7f + noise(i, 104) * 1.4f;
			int color = withAlpha(0xFFFFFFFF, (0.4f + noise(i, 105) * 0.6f) * alpha);
			c.quad(x, y, x + size, y + size, color, color);
		}
	}

	private void shootingStar(Canvas c, float w, float h, float alpha) {
		if (!stars.value()) {
			return;
		}
		float life = 40f;
		float age = time - shootingStarAt;
		if (shootingStarAt < 0f || age >= life) {
			if (time < nextShootingStar) {
				return;
			}
			shootingStarAt = time;
			shootingStarX = random.nextFloat() * w;
			shootingStarY = -12f;
			nextShootingStar = time + 180f + random.nextFloat() * 120f;
			age = 0f;
		}
		// Ten ticks to appear, thirty to go.
		float fade = age < 10f ? age / 10f : Math.max(0f, 1f - (age - 10f) / 30f);
		if (fade <= 0f) {
			return;
		}
		float headX = shootingStarX + age * 4f;
		float headY = shootingStarY + age * 2.3f;
		c.seg(headX, headY, headX - 34.6f, headY - 20f, 1.4f,
				withAlpha(0xFFFFFFFF, 0.9f * fade * alpha));
	}

	// ----------------------------------------------------------------- fences

	/** Deterministic noise for index {@code i}, in 0..1. */
	private static float noise(int index, int salt) {
		long value = (long) index * 2654435761L + salt * 0x9E3779B97F4A7C15L;
		value ^= value >>> 33;
		value *= 0xFF51AFD7ED558CCDL;
		value ^= value >>> 33;
		return (value >>> 11) / (float) (1L << 53);
	}

	private static int withAlpha(int argb, float factor) {
		int alpha = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, factor)));
		return (alpha << 24) | (argb & 0xFFFFFF);
	}

	private MultiBufferSource.BufferSource batch() {
		if (batch == null) {
			buffer = new ByteBufferBuilder(BUFFER_BYTES);
			batch = MultiBufferSource.immediate(buffer);
		}
		return batch;
	}

	private void dropBatch() {
		if (batch != null) {
			try {
				batch.endBatch();
			} catch (RuntimeException ignored) {
				// Already unusable; it is dropped either way.
			}
			batch = null;
		}
		if (buffer != null) {
			try {
				buffer.close();
			} catch (RuntimeException ignored) {
				// Nothing left to release.
			}
			buffer = null;
		}
	}

	// ---------------------------------------------------------------- canvas

	/**
	 * The painting surface: screen pixels in, vertices on the far plane out.
	 */
	private static final class Canvas {
		private final VertexConsumer vc;
		private final PoseStack.Pose pose;
		private final float width;
		private final float height;
		private final double halfW;
		private final double halfH;
		private final float depth;

		Canvas(VertexConsumer vc, PoseStack.Pose pose, float width, float height,
				double halfW, double halfH, float depth) {
			this.vc = vc;
			this.pose = pose;
			this.width = width;
			this.height = height;
			this.halfW = halfW;
			this.halfH = halfH;
			this.depth = depth;
		}

		void vertex(float x, float y, int argb) {
			float nx = x / width * 2f - 1f;
			float ny = 1f - y / height * 2f;
			vc.addVertex(pose, (float) (nx * halfW), (float) (ny * halfH), -depth).setColor(argb);
		}

		void quad(float x0, float y0, float x1, float y1, int top, int bottom) {
			vertex(x0, y0, top);
			vertex(x0, y1, bottom);
			vertex(x1, y1, bottom);
			vertex(x1, y0, top);
		}

		void poly(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3, int argb) {
			vertex(x0, y0, argb);
			vertex(x1, y1, argb);
			vertex(x2, y2, argb);
			vertex(x3, y3, argb);
		}

		/** A straight run of pixels, expanded into a quad of the given width. */
		void seg(float x0, float y0, float x1, float y1, float thickness, int argb) {
			float dx = x1 - x0;
			float dy = y1 - y0;
			float length = (float) Math.sqrt(dx * dx + dy * dy);
			if (length < 1.0e-4f) {
				return;
			}
			float half = thickness * 0.5f;
			float nx = -dy / length * half;
			float ny = dx / length * half;
			poly(x0 + nx, y0 + ny, x1 + nx, y1 + ny, x1 - nx, y1 - ny, x0 - nx, y0 - ny, argb);
		}

		/** Segments a circle of this size needs to read as round. */
		private static int stepsFor(float rx, float ry) {
			return Math.max(12, Math.min(96, Math.round(Math.max(rx, ry) * 0.6f)));
		}

		void disc(float cx, float cy, float rx, float ry, int argb) {
			int steps = stepsFor(rx, ry);
			float px = cx + rx;
			float py = cy;
			for (int i = 1; i <= steps; i++) {
				double angle = i * Math.PI * 2.0 / steps;
				float nx = cx + (float) Math.cos(angle) * rx;
				float ny = cy + (float) Math.sin(angle) * ry;
				// The centre repeats, which makes the quad one triangle.
				poly(cx, cy, px, py, nx, ny, cx, cy, argb);
				px = nx;
				py = ny;
			}
		}

		void ring(float cx, float cy, float rx, float ry, float thickness, int argb, float phase) {
			int steps = stepsFor(rx, ry);
			float innerX = Math.max(1f, rx - thickness);
			float innerY = Math.max(1f, ry - thickness);
			float ox = cx + (float) Math.cos(phase) * rx;
			float oy = cy + (float) Math.sin(phase) * ry;
			float ix = cx + (float) Math.cos(phase) * innerX;
			float iy = cy + (float) Math.sin(phase) * innerY;
			for (int i = 1; i <= steps; i++) {
				double angle = phase + i * Math.PI * 2.0 / steps;
				float nox = cx + (float) Math.cos(angle) * rx;
				float noy = cy + (float) Math.sin(angle) * ry;
				float nix = cx + (float) Math.cos(angle) * innerX;
				float niy = cy + (float) Math.sin(angle) * innerY;
				poly(ix, iy, ox, oy, nox, noy, nix, niy, argb);
				ox = nox;
				oy = noy;
				ix = nix;
				iy = niy;
			}
		}

		/**
		 * A soft radial falloff, built from concentric discs.
		 *
		 * Every disc carries the same share of the requested alpha, so a pixel in
		 * the middle is covered by all of them and a pixel at the rim by one: the
		 * result fades outwards from full strength to nothing.
		 */
		void glow(float cx, float cy, float rx, float ry, int argb, int steps) {
			int baseAlpha = (argb >>> 24) & 0xFF;
			if (baseAlpha == 0 || steps <= 0) {
				return;
			}
			int rgb = argb & 0xFFFFFF;
			int share = Math.max(1, Math.round(baseAlpha / (float) steps));
			int color = (share << 24) | rgb;
			for (int i = steps; i >= 1; i--) {
				float t = i / (float) steps;
				disc(cx, cy, rx * t, ry * t, color);
			}
		}
	}

	// ---------------------------------------------------------- persistence

	@Override
	public void loadConfig(JsonObject json) {
		theme.select(ConfigValues.stringValue(json, "theme", theme.option()));
		speed.set(ConfigValues.intValue(json, "speed", 10));
		opacity.set(ConfigValues.intValue(json, "opacity", 100));
		stars.set(ConfigValues.boolValue(json, "stars", true));
		fog.set(ConfigValues.boolValue(json, "fog", false));
	}

	@Override
	public void saveConfig(JsonObject json) {
		json.addProperty("theme", theme.option());
		json.addProperty("speed", speed.get());
		json.addProperty("opacity", opacity.get());
		json.addProperty("stars", stars.value());
		json.addProperty("fog", fog.value());
	}
}
