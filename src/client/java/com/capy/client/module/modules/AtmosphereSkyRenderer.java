package com.capy.client.module.modules;

import com.capy.client.util.Guard;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldTerrainRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.level.material.FogType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The animated, theme-owned skybox.
 *
 * <h2>A real dome, not a tint</h2>
 * A sky that is only a colour cannot move when the player turns, cannot have a
 * moon in one direction and a storm in another, and cannot make an aurora hang
 * above the horizon. This renderer submits a low-poly dome around the camera
 * before terrain is drawn. The dome is world-oriented: turning the camera
 * changes which part of it is in front, which is the difference between a sky
 * and a screen-space gradient.
 *
 * <h2>Depth</h2>
 * The dome is drawn through {@link AtmosphereRenderTypes#sky()}: no depth test,
 * no depth write. It therefore replaces the vanilla sky without competing with
 * terrain for depth, and every solid or cutout block drawn afterwards covers
 * it normally. The dome itself is opaque; the clouds, stars, aurora and sun
 * are translucent layers over it.
 *
 * <h2>Budget</h2>
 * The base dome is 64 by 48 segments, cached until the theme changes. Each
 * theme adds only a few dozen small quads. The whole sky is a static buffer
 * source reused between frames, so a phone pays for vertices, not for
 * allocations.
 */
public final class AtmosphereSkyRenderer {
	private static final int BUFFER_BYTES = 1024 * 1024;
	private static final double RADIUS = 96.0;
	private static final int SEGMENTS = 64;
	private static final int RINGS = 48;

	private static MultiBufferSource.BufferSource batch;
	private static ByteBufferBuilder buffer;
	/** Static dome vertices for the last theme; rebuilt only when the theme changes. */
	private static float[] domePositions;
	private static float[] domeYaw;
	private static float[] domePitch;
	private static int[] domeColors;
	private static int domeTheme = -1;
	private static final Logger LOGGER = LoggerFactory.getLogger(AtmosphereSkyRenderer.class);
	private static long lastDebugLog;
	private static final PoseStack FALLBACK_STACK = new PoseStack();
	private static final Sky SKY = new Sky();

	private AtmosphereSkyRenderer() {
	}

	public static void render(WorldTerrainRenderContext context) {
		if (!AtmosphereState.active) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return;
		}
		// Underwater the vanilla fog is the sky: a crisp painted dome there
		// would shine through the water fog as a hole in the ocean.
		if (mc.gameRenderer.getMainCamera().getFluidInCamera() != FogType.NONE) {
			return;
		}
		RenderType type = AtmosphereRenderTypes.sky();
		if (type == null) {
			return;
		}
		long passStart = AtmosphereState.debugTiming ? System.nanoTime() : 0L;
		try {
			AtmosphereState.advance();
			PoseStack stack = context instanceof WorldRenderContext full && full.matrices() != null
					? full.matrices() : FALLBACK_STACK;
			MultiBufferSource.BufferSource batches = batch();
			VertexConsumer vc = batches.getBuffer(type);
			Sky sky = SKY;
			sky.set(vc, stack.last());
			double now = System.nanoTime() / 1.0e9;
			int theme = AtmosphereState.theme;
			float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
			long dayTime = mc.level.getDayTime();
			AtmosphereSkyModel.Sun sun = AtmosphereSkyModel.sun(AtmosphereState.timeDay, dayTime);
			float rain = Math.max(0f, Math.min(1f, mc.level.getRainLevel(partial)));
			sky.dome(theme, sun);
			if (AtmosphereState.skyDebug) {
				sky.debug(now);
			} else {
				theme(sky, theme, now, sun, rain, dayTime);
			}
			batches.endBatch();
			if (passStart != 0L) {
				long at = System.nanoTime();
				if (at - lastDebugLog >= 5_000_000_000L) {
					lastDebugLog = at;
					LOGGER.info("Capy atmosphere sky pass: {} ns", at - passStart);
				}
			}
		} catch (RuntimeException | LinkageError failure) {
			discard();
			Guard.report("atmosphere skybox", failure);
		}
	}

	private static void theme(Sky sky, int theme, double now, AtmosphereSkyModel.Sun sun,
			float rain, long dayTime) {
		switch (theme) {
			case 1 -> goldenHour(sky, now, sun, rain, dayTime);
			case 2 -> midnight(sky, now, sun, rain, dayTime);
			case 3 -> aurora(sky, now, sun, rain, dayTime);
			case 4 -> overcast(sky, now, sun, rain, dayTime);
			case 5 -> deepOcean(sky, now, sun, rain, dayTime);
			case 6 -> crimson(sky, now, sun, rain, dayTime);
			case 7 -> sakura(sky, now, sun, rain, dayTime);
			case 8 -> voidSky(sky, now, sun, rain, dayTime);
			case 9 -> neon(sky, now, sun, rain, dayTime);
			default -> gentle(sky, now, sun, rain, dayTime);
		}
	}

	/** One sun disc, drawn only when the solar model says it is up. */
	private static void sunDisc(Sky sky, AtmosphereSkyModel.Sun sun, int theme,
			boolean rays, double now, float rain) {
		if (sun.elevation() <= -0.035f || rain > 0.62f) {
			return;
		}
		double radius = switch (theme) {
			case 1 -> 8.2;
			case 6 -> 8.8;
			case 5 -> 5.8;
			default -> 5.2;
		};
		if (rays) {
			sky.rays(sun.yaw(), sun.pitch(), 17, now * 0.06,
					withAlpha(AtmosphereSkyModel.sunGlow(theme), 0x30));
		}
		sky.sun(sun.yaw(), sun.pitch(), radius,
				AtmosphereSkyModel.sunColor(theme), AtmosphereSkyModel.sunGlow(theme));
	}

	/** The moon opposite the sun, with the world's real phase. */
	private static void moonDisc(Sky sky, AtmosphereSkyModel.Sun sun, int theme,
			long dayTime, float visibility, float rain) {
		visibility *= 1f - rain * 0.85f;
		if (visibility <= 0.02f || sun.night() <= 0.03f) {
			return;
		}
		double yaw = sun.yaw() + 180.0;
		double pitch = -sun.pitch();
		if (pitch < -1.0) {
			return;
		}
		float phase = AtmosphereSkyModel.moonPhase(dayTime);
		int lit = theme == 6 ? 0xFFFFD8C0 : 0xFFF2F6FF;
		int shadow = theme == 6 ? 0xFF2A1010 : 0xFF101827;
		sky.moon(yaw, pitch, 7.0, phase, lit, shadow, 48);
		sky.ellipse(yaw + 1.0, pitch + 1.2, 1.2, 1.8, 0x30223758, 5);
		sky.ellipse(yaw - 0.9, pitch - 1.4, 1.0, 1.2, 0x28223758, 4);
	}

	/** Stars fade out as the sun rises; they are not painted onto a blue sky. */
	private static void stars(Sky sky, double now, AtmosphereSkyModel.Sun sun,
			int count, int seed, float strength, int color, float rain) {
		float visibility = AtmosphereSkyModel.starVisibility(sun) * strength
				* (1f - rain * 0.92f);
		if (visibility <= 0.02f) {
			return;
		}
		for (int i = 0; i < count; i++) {
			double yaw = hash(i * 17 + seed) * 360.0;
			double pitch = 4.0 + hash(i * 37 + seed + 3) * 86.0;
			double twinkle = 0.35 + 0.65 * Math.sin(now * 1.3 + i * 2.1 + seed);
			int alpha = (int) ((30 + twinkle * 155) * visibility);
			sky.star(yaw, pitch, i % 13 == 0 ? 0.48 : 0.24, alpha, color);
		}
	}

	/**
	 * A multi-layer cloud deck. The same geometry is used by every theme; the
	 * palette, coverage and wind speed make it read as haze, storm or sunset
	 * cloud. The extra layer is deliberately slower and larger: that parallax
	 * is what stops the cloud field looking like one flat sheet.
	 */
	private static void cloudDeck(Sky sky, double now, AtmosphereSkyModel.Sun sun,
			float rain, int count, double basePitch, double width, double speed,
			int lit, int shade) {
		int total = Math.max(1, count + Math.round(rain * count * 0.9f));
		for (int i = 0; i < total; i++) {
			double yaw = i * (360.0 / total) + now * speed * (0.82 + (i % 3) * 0.08);
			double pitch = basePitch + ((i % 4) - 1.5) * 4.2
					+ Math.sin(now * 0.055 + i * 1.7) * 1.4;
			double w = width * (0.70 + hash(i * 53 + 5) * 0.60);
			double h = w * (0.18 + hash(i * 71 + 9) * 0.12);
			int l = scaleAlpha(lit, 0.86f + rain * 0.14f);
			int s = scaleAlpha(shade, 0.86f + rain * 0.20f);
			sky.cloud(yaw, pitch, w, h, l, s, sun.yaw());
		}
	}

	private static int scaleAlpha(int color, float factor) {
		int alpha = Math.round(((color >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, factor)));
		return withAlpha(color, alpha);
	}

	/** A calm blue sky: warm sun, layered clouds and faint high stars. */
	private static void gentle(Sky sky, double now, AtmosphereSkyModel.Sun sun,
			float rain, long dayTime) {
		sunDisc(sky, sun, 0, false, now, rain);
		moonDisc(sky, sun, 0, dayTime, 0.85f, rain);
		stars(sky, now, sun, 34, 4, 0.9f, 0xFFE8F6FF, rain);
		cloudDeck(sky, now, sun, rain, 8, 24.0, 12.0, 0.72,
				AtmosphereSkyModel.cloudLit(0, sun), AtmosphereSkyModel.cloudShade(0, sun));
		cloudDeck(sky, now, sun, rain * 0.5f, 4, 36.0, 8.0, 0.38,
				scaleAlpha(AtmosphereSkyModel.cloudLit(0, sun), 0.55f),
				scaleAlpha(AtmosphereSkyModel.cloudShade(0, sun), 0.55f));
		double sheen = 0.5 + 0.5 * Math.sin(now * 0.18);
		sky.band(-30 + Math.sin(now * 0.05) * 24, 6, 72, 18,
				0x006C9BB8, withAlpha(0xFF8FC4E8, (int) (8 + sheen * 14)));
	}

	/** Golden Hour: a large low sun, amber cloud layers and visible rays. */
	private static void goldenHour(Sky sky, double now, AtmosphereSkyModel.Sun sun,
			float rain, long dayTime) {
		sunDisc(sky, sun, 1, true, now, rain);
		moonDisc(sky, sun, 1, dayTime, 0.25f, rain);
		stars(sky, now, sun, 12, 8, 0.35f, 0xFFFFE8C8, rain);
		cloudDeck(sky, now, sun, rain, 12, 18.0, 15.0, 0.95,
				AtmosphereSkyModel.cloudLit(1, sun), AtmosphereSkyModel.cloudShade(1, sun));
		cloudDeck(sky, now, sun, rain * 0.5f, 5, 30.0, 9.0, 0.55,
				scaleAlpha(AtmosphereSkyModel.cloudLit(1, sun), 0.55f),
				scaleAlpha(AtmosphereSkyModel.cloudShade(1, sun), 0.55f));
		for (int i = 0; i < 6; i++) {
			double yaw = sun.yaw() + Math.sin(i * 2.1 + now * 0.04) * 40;
			double shimmer = 0.5 + 0.5 * Math.sin(now * 0.42 + i * 1.7);
			sky.band(yaw, 3, 9, 13, 0x001F0E08,
					withAlpha(0xFFFFB06A, (int) (20 + shimmer * 24)));
		}
	}

	/** Midnight: moon with craters, a dense star field and a shooting star. */
	private static void midnight(Sky sky, double now, AtmosphereSkyModel.Sun sun,
			float rain, long dayTime) {
		// The preset defaults to midnight, but the clock is still a clock: if
		// the player moves it to noon, this theme has to show a real day too.
		sunDisc(sky, sun, 2, false, now, rain);
		moonDisc(sky, sun, 2, dayTime, 1f, rain);
		stars(sky, now, sun, 130, 3, 1f, 0xFFFFFFFF, rain);
		double cycle = now % 30.0;
		if (sun.night() > 0.5f && cycle < 1.2) {
			double pass = cycle / 1.2;
			double sy = -80 + pass * 160;
			sky.streak(sy, 62 - pass * 18, sy + 12.6, 59 - pass * 18,
					0xE8FFFFFF, 0x00FFFFFF);
		}
		cloudDeck(sky, now, sun, rain, 5, 32.0, 11.0, 0.25,
				AtmosphereSkyModel.cloudLit(2, sun), AtmosphereSkyModel.cloudShade(2, sun));
	}

	/** Aurora: living curtains behind a cold star field. */
	private static void aurora(Sky sky, double now, AtmosphereSkyModel.Sun sun,
			float rain, long dayTime) {
		sunDisc(sky, sun, 3, false, now, rain);
		moonDisc(sky, sun, 3, dayTime, 0.4f, rain);
		stars(sky, now, sun, 100, 5, 0.85f, 0xFFC9FFF4, rain);
		float night = (0.08f + 0.92f * sun.night()) * (1f - rain * 0.70f);
		sky.curtain(-80, 160, 17, 59, 22, now * 0.22,
				scaleAlpha(0x8C4DFFC4, night), scaleAlpha(0x006CFFB2, night));
		sky.curtain(-20, 160, 24, 70, 18, now * 0.17 + 1.7,
				scaleAlpha(0x7A7195FF, night), scaleAlpha(0x203A59FF, night));
		sky.curtain(-120, 140, 13, 48, 16, now * 0.26 + 3.1,
				scaleAlpha(0x5C5CFFE0, night), scaleAlpha(0x0044D6A4, night));
		cloudDeck(sky, now, sun, rain, 5, 12.0, 13.0, 0.55,
				AtmosphereSkyModel.cloudLit(3, sun), AtmosphereSkyModel.cloudShade(3, sun));
	}

	/** Overcast: rolling storm layers and distant sheet lightning. */
	private static void overcast(Sky sky, double now, AtmosphereSkyModel.Sun sun,
			float rain, long dayTime) {
		int count = 16 + Math.round(rain * 18f);
		cloudDeck(sky, now, sun, rain, count, 28.0, 18.0, 1.35,
				AtmosphereSkyModel.cloudLit(4, sun), AtmosphereSkyModel.cloudShade(4, sun));
		cloudDeck(sky, now, sun, rain * 0.7f, 9, 12.0, 13.0, 0.75,
				scaleAlpha(AtmosphereSkyModel.cloudLit(4, sun), 0.65f),
				scaleAlpha(AtmosphereSkyModel.cloudShade(4, sun), 0.65f));
		double flash = Math.pow(Math.max(0.0, Math.sin(now * 0.62 + 1.3)), 24.0);
		if (flash > 0.01) {
			sky.band(-40 + Math.sin(now * 0.4) * 24, 7, 40, 38,
					0x00EAF4FF, withAlpha(0xFFEAF4FF, (int) (flash * 150)));
		}
		for (int i = 0; i < 26; i++) {
			double yaw = hash(i * 31 + 2) * 360;
			double pitch = 12 + hash(i * 19 + 7) * 54;
			sky.streak(yaw, pitch, yaw + 0.35, pitch - 3.5,
					0x385D6C7C, 0x005D6C7C);
		}
	}

	/** Deep Ocean: the water surface above, caustics, shafts and bubbles. */
	private static void deepOcean(Sky sky, double now, AtmosphereSkyModel.Sun sun,
			float rain, long dayTime) {
		sunDisc(sky, sun, 5, false, now, rain);
		for (int i = 0; i < 11; i++) {
			double yaw = i * 33 + now * 0.9;
			double pitch = 48 + (i % 4) * 8;
			double sway = Math.sin(now * 0.4 + i * 1.2) * 4;
			sky.band(yaw, pitch + sway, 4.0 + i % 3 * 1.5, 2.5,
					0x0035C8E0, 0x3A9BF6FF);
		}
		for (int i = 0; i < 6; i++) {
			double yaw = i * 60 + now * 1.2;
			sky.band(yaw, 82, 2.4, 58, 0x0068E8FF, 0x2458D8F0);
		}
		for (int i = 0; i < 34; i++) {
			double yaw = hash(i * 23 + 4) * 360 + Math.sin(now + i) * 1.2;
			double phase = (hash(i * 41 + 9) + now * (0.018 + hash(i * 7) * 0.02)) % 1.0;
			double pitch = 8 + phase * 64;
			int alpha = 35 + (int) (hash(i * 13 + 6) * 55);
			sky.ellipse(yaw, pitch, 0.45, 0.45, withAlpha(0xFFB8F4FF, alpha), 3);
		}
	}

	/** Crimson: a red sun under smoke, with sparks in the air. */
	private static void crimson(Sky sky, double now, AtmosphereSkyModel.Sun sun,
			float rain, long dayTime) {
		sunDisc(sky, sun, 6, true, now, rain);
		moonDisc(sky, sun, 6, dayTime, 0.22f, rain);
		if (sun.elevation() > -0.035f) {
			double pulse = 8.0 + Math.sin(now * 0.42) * 1.4;
			sky.ellipse(sun.yaw(), sun.pitch(), pulse * 3.4, pulse * 3.4, 0x12FF2A18, 32);
		}
		cloudDeck(sky, now, sun, rain, 10, 18.0, 16.0, 0.55,
				AtmosphereSkyModel.cloudLit(6, sun), AtmosphereSkyModel.cloudShade(6, sun));
		for (int i = 0; i < 40; i++) {
			double yaw = hash(i * 17 + 8) * 360 + Math.sin(now * 0.3 + i) * 3;
			double pitch = 10 + (hash(i * 29 + 5) + now * 0.02) % 1.0 * 55;
			double twinkle = 0.3 + 0.7 * Math.sin(now * 2.0 + i * 1.9);
			sky.star(yaw, pitch, 0.2 + hash(i) * 0.25, (int) (twinkle * 120), 0xFFFF8A42);
		}
	}

	/** Sakura: a rose dawn with layered clouds and drifting sky petals. */
	private static void sakura(Sky sky, double now, AtmosphereSkyModel.Sun sun,
			float rain, long dayTime) {
		sunDisc(sky, sun, 7, false, now, rain);
		moonDisc(sky, sun, 7, dayTime, 0.2f, rain);
		cloudDeck(sky, now, sun, rain, 8, 21.0, 14.0, 0.85,
				AtmosphereSkyModel.cloudLit(7, sun), AtmosphereSkyModel.cloudShade(7, sun));
		for (int i = 0; i < 3; i++) {
			double yaw = i * 120 - now * 0.4;
			sky.band(yaw, 8 + i * 3, 34, 7, 0x00FFD0E2, 0x14FFC6DE);
		}
		for (int i = 0; i < 32; i++) {
			double yaw = hash(i * 19 + 7) * 360 + Math.sin(now * 0.15 + i) * 4.5;
			double pitch = 10 + (hash(i * 31 + 3) + now * 0.012) % 1.0 * 66;
			double sway = Math.sin(now * 0.8 + i * 1.4) * 2.2;
			sky.diamond(yaw + sway, pitch, 0.32 + hash(i * 5) * 0.35,
					0xB0FFD0E2, 0x70FFF4FA);
		}
	}

	/** Void: a dying star, a black hole and slow nebula bands. */
	private static void voidSky(Sky sky, double now, AtmosphereSkyModel.Sun sun,
			float rain, long dayTime) {
		stars(sky, now, sun, 110, 9, 1f, 0xFF9F7AD8, rain);
		for (int i = 0; i < 7; i++) {
			double yaw = i * 63 + now * 0.25;
			sky.band(yaw, 20 + i % 3 * 7, 30 + i % 2 * 18, 36 + i * 3,
					0x102A0E42, 0x263E1C6A);
		}
		double bx = -32.0;
		double by = 38.0;
		sky.ellipse(bx, by, 9.0, 9.0, 0xFF07030D, 32);
		sky.ellipse(bx, by, 12.0, 12.0, 0x304D2C80, 32);
		sky.ellipse(bx, by, 15.0, 15.0, 0x166A42A8, 32);
		for (int i = 0; i < 32; i++) {
			double angle = now * 0.08 + i * (Math.PI * 2.0 / 32.0);
			sky.star(bx + Math.cos(angle) * 14.5, by + Math.sin(angle) * 13.2,
					0.18, 55, 0xFFB48CFF);
		}
		for (int i = 0; i < 3; i++) {
			double angle = now * 0.11 + i * 2.1;
			sky.band(bx + Math.cos(angle) * 5.0, by + Math.sin(angle) * 4.0,
					4.5, 0.7, 0x003C1A68, 0x3A8A5AD8);
		}
	}

	/** Neon: a banded synthwave sun over a neon horizon grid. */
	private static void neon(Sky sky, double now, AtmosphereSkyModel.Sun sun,
			float rain, long dayTime) {
		double sunYaw = 0.0;
		double sunPitch = 22.0;
		double pulse = 0.5 + 0.5 * Math.sin(now * 0.45);
		sky.ellipse(sunYaw, 5.0, 54.0, 8.0, 0x24FF3FBF, 10);
		sky.ellipse(sunYaw, 2.0, 30.0, 4.5, 0x30FF5AC8, 8);
		sky.ellipse(sunYaw, sunPitch, 38.4, 38.4, 0x14FF48D0, 10);
		sky.ellipse(sunYaw, sunPitch, 24.8, 24.8, 0x28FF48D0, 10);
		sky.stripedSun(sunYaw, sunPitch, 16.0, now);
		cloudDeck(sky, now, sun, rain, 9, 35.0, 17.0, 0.35,
				AtmosphereSkyModel.cloudLit(9, sun), AtmosphereSkyModel.cloudShade(9, sun));
		for (int i = 0; i < 18; i++) {
			double yaw = i * 20 + now * 0.18;
			sky.band(yaw, -14, 0.6, 17, 0x0018F8E8, 0x5A18F8E8);
		}
		for (int i = 0; i < 7; i++) {
			double pitch = -12.0 + i * 2.1;
			int alpha = 0x16 + i * 0x07;
			sky.ring(pitch, 0.55, 32, 0x0018C8D8, withAlpha(0xFF18C8D8, alpha));
		}
		sky.ring(-0.5, 0.9, 32, 0x0020E0D0, 0x7020E0D0);
		double scan = (now * 0.045) % 1.0;
		sky.ring(-2.0 + scan * 24.0, 0.8, 32, 0x0010F8E8, 0x3010F8E8);
		for (int i = 0; i < 24; i++) {
			double yaw = hash(i * 19 + 7) * 360 + now * 0.12;
			double pitch = 34 + hash(i * 31 + 5) * 48;
			double twinkle = 0.35 + 0.65 * Math.sin(now * 1.4 + i * 1.7);
			sky.star(yaw, pitch, i % 6 == 0 ? 0.42 : 0.22,
					(int) (twinkle * 78 + pulse * 18), 0xFFFF9ADC);
		}
	}

	// ------------------------------------------------------------------ sky

	/** Camera-centred geometry helpers. */
	static final class Sky {
		private VertexConsumer vc;
		private PoseStack.Pose pose;

		void set(VertexConsumer vc, PoseStack.Pose pose) {
			this.vc = vc;
			this.pose = pose;
		}

		void dome(int theme) {
			dome(theme, AtmosphereSkyModel.sun(0.5f, 6000L));
		}

		void dome(int theme, AtmosphereSkyModel.Sun sun) {
			ensureDome(theme);
			for (int i = 0; i < domeColors.length; i++) {
				domeColors[i] = AtmosphereSkyModel.skyColor(theme, domeYaw[i], domePitch[i], sun);
				int offset = i * 3;
				vc.addVertex(pose, domePositions[offset], domePositions[offset + 1],
						domePositions[offset + 2]).setColor(domeColors[i]);
			}
		}
		/**
		 * A vertical band between two yaw/pitch corners. Width is specified in
		 * degrees on the great circle, not in raw yaw degrees: one degree of yaw
		 * is almost a whole great-circle degree at the horizon but a fraction of
		 * one near the pole. The correction is what stops clouds and bars
		 * squeezing as they climb.
		 */
		void band(double yaw, double pitch, double width, double height,
				int bottom, int top) {
			vertexAt(greatYaw(yaw, pitch, -width), pitch, RADIUS * 0.985, bottom);
			vertexAt(greatYaw(yaw, pitch, width), pitch, RADIUS * 0.985, bottom);
			vertexAt(greatYaw(yaw, pitch + height, width), pitch + height, RADIUS * 0.985, top);
			vertexAt(greatYaw(yaw, pitch + height, -width), pitch + height, RADIUS * 0.985, top);
		}

		/**
		 * A full latitude line, split into short quads. One wide quad across the
		 * dome becomes a chord through the camera rather than a line on the sky;
		 * these segments stay close to the sphere all the way round.
		 */
		void ring(double pitch, double height, int segments, int bottom, int top) {
			int count = Math.max(3, segments);
			double step = 360.0 / count;
			for (int i = 0; i < count; i++) {
				double yaw = -180.0 + (i + 0.5) * step;
				band(yaw, pitch, step * 0.5, height, bottom, top);
			}
		}

		/** The sun: a crisp opaque core with a soft glow ring around it. */
		void sun(double yaw, double pitch, double radius, int core, int glow) {
			ellipse(yaw, pitch, radius * 2.4, radius * 2.4, withAlpha(glow, 20), 8);
			ellipse(yaw, pitch, radius * 1.55, radius * 1.55, withAlpha(glow, 40), 8);
			disc(yaw, pitch, radius, radius, withAlpha(core, 255), 48, 0.85f);
		}

		/**
		 * A moon with a real terminator. Each vertex is shaded by its horizontal
		 * position on the disc, so the crescent is a shaded surface rather than
		 * a dark circle drawn over a bright one.
		 */
		void moon(double yaw, double pitch, double radius, float phase,
				int lit, int shadow, int segments) {
			ellipse(yaw, pitch, radius * 2.1, radius * 2.1, withAlpha(lit, 16), 8);
			basis(yaw, pitch);
			double dx = basisDx;
			double dy = basisDy;
			double dz = basisDz;
			double rx = basisRx;
			double rz = basisRz;
			double ux = basisUx;
			double uy = basisUy;
			double uz = basisUz;
			double rr = RADIUS * 0.994;
			double sw = Math.sin(Math.toRadians(radius));
			double sh = sw;
			int rim = Math.max(48, Math.min(96, segments));
			int rings = 8;
			for (int ring = 0; ring < rings; ring++) {
				double t0 = (double) ring / rings;
				double t1 = (double) (ring + 1) / rings;
				for (int i = 0; i < rim; i++) {
					double a0 = Math.PI * 2.0 * i / rim;
					double a1 = Math.PI * 2.0 * (i + 1) / rim;
					double c0 = Math.cos(a0);
					double c1 = Math.cos(a1);
					double x0 = dx + (rx * c0 * sw + ux * Math.sin(a0) * sh) * t1;
					double y0 = dy + (uy * Math.sin(a0) * sh) * t1;
					double z0 = dz + (rz * c0 * sw + uz * Math.sin(a0) * sh) * t1;
					double x1 = dx + (rx * c1 * sw + ux * Math.sin(a1) * sh) * t1;
					double y1 = dy + (uy * Math.sin(a1) * sh) * t1;
					double z1 = dz + (rz * c1 * sw + uz * Math.sin(a1) * sh) * t1;
					double ix0 = dx + (rx * c0 * sw + ux * Math.sin(a0) * sh) * t0;
					double iy0 = dy + (uy * Math.sin(a0) * sh) * t0;
					double iz0 = dz + (rz * c0 * sw + uz * Math.sin(a0) * sh) * t0;
					double ix1 = dx + (rx * c1 * sw + ux * Math.sin(a1) * sh) * t0;
					double iy1 = dy + (uy * Math.sin(a1) * sh) * t0;
					double iz1 = dz + (rz * c1 * sw + uz * Math.sin(a1) * sh) * t0;
					float fade = (float) (t1 > 0.85 ? (1.0 - t1) / 0.15 : 1.0);
					vertex(ix0 * rr, iy0 * rr, iz0 * rr, moonColour(c0, phase, lit, shadow, fade));
					vertex(ix1 * rr, iy1 * rr, iz1 * rr, moonColour(c1, phase, lit, shadow, fade));
					vertex(x1 * rr, y1 * rr, z1 * rr, moonColour(c1, phase, lit, shadow, fade));
					vertex(x0 * rr, y0 * rr, z0 * rr, moonColour(c0, phase, lit, shadow, fade));
				}
			}
		}

		/** Radiating wedges, drawn behind a sun as quads. */
		void rays(double yaw, double pitch, int count, double phase, int color) {
			for (int i = 0; i < count; i++) {
				double a = i * Math.PI * 2 / count + phase;
				double spread = 2.0 + 1.3 * Math.sin(i * 1.7);
				double inner = 4.0;
				double outer = 19.0;
				double offset = spread / outer;
				double ia = a - offset * 0.35;
				double ib = a + offset * 0.35;
				double oa = a - offset;
				double ob = a + offset;
				vertexTangent(yaw, pitch, Math.cos(ia) * inner, Math.sin(ia) * inner,
						RADIUS * 0.99, color);
				vertexTangent(yaw, pitch, Math.cos(ib) * inner, Math.sin(ib) * inner,
						RADIUS * 0.99, color);
				vertexTangent(yaw, pitch, Math.cos(ob) * outer, Math.sin(ob) * outer,
						RADIUS * 0.99, withAlpha(color, 0));
				vertexTangent(yaw, pitch, Math.cos(oa) * outer, Math.sin(oa) * outer,
						RADIUS * 0.99, withAlpha(color, 0));
			}
		}

		/** A soft cloud blob made from three overlapping ellipses. */
		void puff(double yaw, double pitch, double width, double height, int color) {
			ellipse(yaw, pitch, width, height, color, 7);
			ellipse(yaw + width * 0.34, pitch + height * 0.18, width * 0.62, height * 0.78,
					withAlpha(color, Math.max(0, (color >>> 24) - 8)), 6);
			ellipse(yaw - width * 0.30, pitch - height * 0.15, width * 0.48, height * 0.62,
					withAlpha(color, Math.max(0, (color >>> 24) - 14)), 5);
		}

		/**
		 * A cloud made from overlapping soft discs. The lit edge is shifted towards
		 * the sun and the shaded body stays underneath, so a cloud has a light
		 * side and a dark side instead of one translucent white blob.
		 */
		void cloud(double yaw, double pitch, double width, double height,
				int lit, int shade, double sunYaw) {
			if (width <= 0.0 || height <= 0.0) {
				return;
			}
			double towardSun = Math.cos(Math.toRadians(yaw - sunYaw));
			double shift = width * 0.16 * towardSun;
			ellipse(yaw, pitch, width, height * 0.72, shade, 8);
			ellipse(yaw - width * 0.30, pitch - height * 0.16,
					width * 0.58, height * 0.46, shade, 7);
			ellipse(yaw + shift, pitch + height * 0.12,
					width * 0.74, height * 0.50, lit, 8);
			ellipse(yaw + shift + width * 0.28, pitch + height * 0.22,
					width * 0.38, height * 0.30, lit, 6);
		}

		/** A small star: a soft glow, a tight core and a faint cross. */
		void star(double yaw, double pitch, double size, int alpha, int color) {
			if (alpha <= 2) {
				return;
			}
			ellipse(yaw, pitch, size * 1.8, size * 1.8, withAlpha(color, alpha / 3), 14);
			ellipse(yaw, pitch, size, size, withAlpha(color, alpha), 16);
			int cross = withAlpha(color, Math.min(255, alpha * 2 / 3));
			band(yaw, pitch, size * 0.55, 0.035, 0x00000000, cross);
			band(yaw, pitch, 0.035, size * 0.55, 0x00000000, cross);
		}

		/** A falling streak: a tapered line across the dome. */
		void streak(double yaw0, double pitch0, double yaw1, double pitch1,
				int head, int tail) {
			double width = 0.10;
			vertexAt(greatYaw(yaw0, pitch0, -width), pitch0, RADIUS * 0.985, tail);
			vertexAt(greatYaw(yaw0, pitch0, width), pitch0, RADIUS * 0.985, tail);
			vertexAt(greatYaw(yaw1, pitch1, width), pitch1, RADIUS * 0.985, head);
			vertexAt(greatYaw(yaw1, pitch1, -width), pitch1, RADIUS * 0.985, head);
		}

		/** A diamond, used for petals and sparks high in the sky. */
		void diamond(double yaw, double pitch, double size, int a, int b) {
			ellipse(yaw, pitch, size, size * 1.6, b, 32);
			ellipse(yaw, pitch, size * 0.55, size * 0.9, a, 32);
		}

		/**
		 * A curtain. The lower edge is almost transparent and the higher edge is
		 * brighter; the wave is what makes it move like light in the air.
		 */
		void curtain(double start, double span, double low, double high,
				int segments, double phase, int bright, int lowColor) {
			for (int i = 0; i < segments; i++) {
				double t0 = (double) i / segments;
				double t1 = (double) (i + 1) / segments;
				double y0 = start + span * t0;
				double y1 = start + span * t1;
				double wave0 = Math.sin(t0 * 7 + phase) * 5.0 + Math.sin(t0 * 13 - phase) * 2.2;
				double wave1 = Math.sin(t1 * 7 + phase) * 5.0 + Math.sin(t1 * 13 - phase) * 2.2;
				int edge = withAlpha(bright, 0);
				vertexAt(y0, low + wave0, RADIUS * 0.992, edge);
				vertexAt(y1, low + wave1, RADIUS * 0.992, edge);
				vertexAt(y1, high + wave1, RADIUS * 0.992, bright);
				vertexAt(y0, high + wave0, RADIUS * 0.992, bright);
				// A darker under-curtain doubles the apparent depth.
				vertexAt(y0, low + wave0 + 1, RADIUS * 0.991, lowColor);
				vertexAt(y1, low + wave1 + 1, RADIUS * 0.991, lowColor);
				vertexAt(y1, high + wave1 + 1, RADIUS * 0.991, withAlpha(lowColor, 0));
				vertexAt(y0, high + wave0 + 1, RADIUS * 0.991, withAlpha(lowColor, 0));
			}
		}

		/**
		 * A sun cut into horizontal strips. The lower strips contain real gaps,
		 * so the sky shows through instead of an opaque bar being painted over
		 * the disc. Gap rows grow towards the bottom and drift with the phase.
		 */
		void stripedSun(double yaw, double pitch, double radius, double now) {
			int rows = 32;
			int cols = 10;
			int drift = (int) Math.round(Math.sin(now * 0.35));
			for (int row = 0; row < rows; row++) {
				double y0 = -radius + radius * 2.0 * row / rows;
				double y1 = -radius + radius * 2.0 * (row + 1) / rows;
				double middle = (y0 + y1) * 0.5;
				if (middle < radius * 0.52) {
					int gapEvery = middle < 0 ? 3 : 4;
					if (Math.floorMod(row + drift + (middle < 0 ? 1 : 0), gapEvery) == 0) {
						continue;
					}
				}
				double half = Math.sqrt(Math.max(0.0, radius * radius - middle * middle));
				if (half <= 0.0) {
					continue;
				}
				for (int col = 0; col < cols; col++) {
					double x0 = -half + half * 2.0 * col / cols;
					double x1 = -half + half * 2.0 * (col + 1) / cols;
					double cx = (x0 + x1) * 0.5;
					double cy = middle;
					double distance = Math.sqrt(cx * cx + cy * cy);
					int alpha = distance > radius * 0.93 ? 120 : 255;
					int color = withAlpha(0xFFFF8ADA, alpha);
					vertexTangent(yaw, pitch, x0, y0, RADIUS * 0.997, color);
					vertexTangent(yaw, pitch, x1, y0, RADIUS * 0.997, color);
					vertexTangent(yaw, pitch, x1, y1, RADIUS * 0.997, color);
					vertexTangent(yaw, pitch, x0, y1, RADIUS * 0.997, color);
				}
			}
		}

		/** One flat-coloured sample of every primitive, with compass marks. */
		void debug(double now) {
			disc(0.0, 35.0, 9.0, 9.0, 0xFFFF4040, 24, 0.8f);
			band(0.0, 8.0, 24.0, 8.0, 0xFF00E060, 0xFF00FF80);
			ring(55.0, 1.2, 48, 0xFFFFD000, 0xFFFFF060);
			puff(-60.0, 34.0, 8.0, 5.0, 0xFF4080FF);
			star(80.0, 46.0, 1.4, 255, 0xFFFFFFFF);
			curtain(120.0, 42.0, 22.0, 58.0, 12, now * 0.2, 0xFFFF40FF, 0x8040FF80);
			streak(-120.0, 54.0, -108.0, 48.0, 0xFFFFFF00, 0x00FF8000);
			rays(170.0, 38.0, 12, 0.0, 0xFFFF8000);
			diamond(-30.0, 18.0, 1.2, 0xFF00FFFF, 0xFF80FFFF);
			for (int i = 0; i < 4; i++) {
				double mark = i * 90.0;
				star(mark, 0.0, 1.0, 255, i % 2 == 0 ? 0xFFFF0000 : 0xFF00FFFF);
				band(mark, -1.0, 3.0, 3.0, 0x00FFFFFF, 0xFFFFFFFF);
			}
			ring(30.0, 0.35, 96, 0x4020A0FF, 0x8040C0FF);
			ring(60.0, 0.35, 96, 0x4020A0FF, 0x8040C0FF);
		}

		/**
		 * An ellipse on a plane facing the dome's centre. Drawing it in strips
		 * keeps the vertex format to quads, which the position-colour pipeline
		 * expects, while still producing a round silhouette.
		 */
		void ellipse(double yaw, double pitch, double halfWidth, double halfHeight,
				int color, int strips) {
			disc(yaw, pitch, halfWidth, halfHeight, color,
					Math.max(16, Math.min(64, strips * 2)), 0f);
		}

		/** A radius fade from a solid centre to a transparent rim, in quads. */
		void disc(double yaw, double pitch, double halfWidth, double halfHeight,
				int color, int segments, float coreFraction) {
			if (halfWidth <= 0 || halfHeight <= 0) {
				return;
			}
			basis(yaw, pitch);
			double dx = basisDx;
			double dy = basisDy;
			double dz = basisDz;
			double rx = basisRx;
			double rz = basisRz;
			double ux = basisUx;
			double uy = basisUy;
			double uz = basisUz;
			double sw = Math.sin(Math.toRadians(halfWidth));
			double sh = Math.sin(Math.toRadians(halfHeight));
			double rr = RADIUS * 0.994;
			int rim = Math.max(16, Math.min(96, segments));
			int rings = coreFraction > 0.5f ? 6 : 5;
			for (int ring = 0; ring < rings; ring++) {
				double t0 = (double) ring / rings;
				double t1 = (double) (ring + 1) / rings;
				int c0 = discColour(color, t0, coreFraction);
				int c1 = discColour(color, t1, coreFraction);
				for (int i = 0; i < rim; i++) {
					double a0 = Math.PI * 2.0 * i / rim;
					double a1 = Math.PI * 2.0 * (i + 1) / rim;
					double ix0 = dx + (rx * Math.cos(a0) * sw + ux * Math.sin(a0) * sh) * t0;
					double iy0 = dy + (uy * Math.sin(a0) * sh) * t0;
					double iz0 = dz + (rz * Math.cos(a0) * sw + uz * Math.sin(a0) * sh) * t0;
					double ix1 = dx + (rx * Math.cos(a1) * sw + ux * Math.sin(a1) * sh) * t0;
					double iy1 = dy + (uy * Math.sin(a1) * sh) * t0;
					double iz1 = dz + (rz * Math.cos(a1) * sw + uz * Math.sin(a1) * sh) * t0;
					double ox0 = dx + (rx * Math.cos(a0) * sw + ux * Math.sin(a0) * sh) * t1;
					double oy0 = dy + (uy * Math.sin(a0) * sh) * t1;
					double oz0 = dz + (rz * Math.cos(a0) * sw + uz * Math.sin(a0) * sh) * t1;
					double ox1 = dx + (rx * Math.cos(a1) * sw + ux * Math.sin(a1) * sh) * t1;
					double oy1 = dy + (uy * Math.sin(a1) * sh) * t1;
					double oz1 = dz + (rz * Math.cos(a1) * sw + uz * Math.sin(a1) * sh) * t1;
					vertex(ix0 * rr, iy0 * rr, iz0 * rr, c0);
					vertex(ix1 * rr, iy1 * rr, iz1 * rr, c0);
					vertex(ox1 * rr, oy1 * rr, oz1 * rr, c1);
					vertex(ox0 * rr, oy0 * rr, oz0 * rr, c1);
				}
			}
		}

		private int discColour(int color, double t, float coreFraction) {
			if (t <= coreFraction) {
				return color;
			}
			double fade = 1.0 - (t - coreFraction) / Math.max(1.0e-5, 1.0 - coreFraction);
			return withAlpha(color, (int) Math.round(((color >>> 24) & 0xFF) * fade * fade));
		}

		private int moonColour(double x, float phase, int lit, int shadow, float fade) {
			float light = AtmosphereMath.moonLight((float) x, phase);
			int rgb = mix(shadow, lit, light);
			return withAlpha(rgb, (int) Math.round(((lit >>> 24) & 0xFF) * fade));
		}

		private double basisDx;
		private double basisDy;
		private double basisDz;
		private double basisRx;
		private double basisRz;
		private double basisUx;
		private double basisUy;
		private double basisUz;

		/** Reusable tangent basis; no allocation in the per-frame sky path. */
		private void basis(double yaw, double pitch) {
			double yawRad = Math.toRadians(yaw);
			double pitchRad = Math.toRadians(pitch);
			double cp = Math.cos(pitchRad);
			basisDx = Math.sin(yawRad) * cp;
			basisDy = Math.sin(pitchRad);
			basisDz = Math.cos(yawRad) * cp;
			double rx = basisDz;
			double rz = -basisDx;
			double length = Math.hypot(rx, rz);
			if (length < 1.0e-5) {
				rx = 1.0;
				rz = 0.0;
				length = 1.0;
			}
			rx /= length;
			rz /= length;
			basisRx = rx;
			basisRz = rz;
			basisUx = basisDy * rz;
			basisUy = basisDz * rx - basisDx * rz;
			basisUz = -basisDy * rx;
		}
		private void vertexAt(double yawDegrees, double pitchDegrees, double radius, int color) {
			double yaw = Math.toRadians(yawDegrees);
			double pitch = Math.toRadians(pitchDegrees);
			double cp = Math.cos(pitch);
			vertex(Math.sin(yaw) * cp * radius, Math.sin(pitch) * radius,
					Math.cos(yaw) * cp * radius, color);
		}

		/** Horizontal angular offset converted to the great circle at pitch. */
		private static double greatYaw(double yaw, double pitch, double offset) {
			double scale = Math.max(0.20, Math.cos(Math.toRadians(pitch)));
			return yaw + offset / scale;
		}

		/** A point in the tangent plane at (yaw, pitch), offsets in degrees. */
		private void vertexTangent(double yaw, double pitch, double right, double up,
				double radius, int color) {
			vertexAt(greatYaw(yaw, pitch, right), pitch + up, radius, color);
		}

		private void vertex(double x, double y, double z, int color) {
			vc.addVertex(pose, (float) x, (float) y, (float) z).setColor(color);
		}
	}

	private static void ensureDome(int theme) {
		if (theme == domeTheme && domePositions != null) {
			return;
		}
		int quads = SEGMENTS * RINGS;
		float[] positions = new float[quads * 4 * 3];
		float[] yaws = new float[quads * 4];
		float[] pitches = new float[quads * 4];
		double lower = -14;
		double upper = 90;
		int index = 0;
		for (int ring = 0; ring < RINGS; ring++) {
			double p0 = lower + (upper - lower) * ring / RINGS;
			double p1 = lower + (upper - lower) * (ring + 1) / RINGS;
			for (int segment = 0; segment < SEGMENTS; segment++) {
				double y0 = 360.0 * segment / SEGMENTS;
				double y1 = 360.0 * (segment + 1) / SEGMENTS;
				index = writeDomeVertex(positions, yaws, pitches, index, y0, p0);
				index = writeDomeVertex(positions, yaws, pitches, index, y1, p0);
				index = writeDomeVertex(positions, yaws, pitches, index, y1, p1);
				index = writeDomeVertex(positions, yaws, pitches, index, y0, p1);
			}
		}
		domePositions = positions;
		domeYaw = yaws;
		domePitch = pitches;
		domeColors = new int[quads * 4];
		domeTheme = theme;
	}

	private static int writeDomeVertex(float[] positions, float[] yaws, float[] pitches,
			int index, double yawDegrees, double pitchDegrees) {
		double yaw = Math.toRadians(yawDegrees);
		double pitch = Math.toRadians(pitchDegrees);
		double cp = Math.cos(pitch);
		int offset = index * 3;
		positions[offset] = (float) (Math.sin(yaw) * cp * RADIUS);
		positions[offset + 1] = (float) (Math.sin(pitch) * RADIUS);
		positions[offset + 2] = (float) (Math.cos(yaw) * cp * RADIUS);
		yaws[index] = (float) yawDegrees;
		pitches[index] = (float) pitchDegrees;
		return index + 1;
	}

	private static int mix(int a, int b, float t) {
		float f = Math.max(0f, Math.min(1f, t));
		// Smoothstep: a linear ramp still shows its ends as bands.
		f = f * f * (3f - 2f * f);
		int aa = (a >>> 24) & 0xFF;
		int ar = (a >> 16) & 0xFF;
		int ag = (a >> 8) & 0xFF;
		int ab = a & 0xFF;
		int ba = (b >>> 24) & 0xFF;
		int br = (b >> 16) & 0xFF;
		int bg = (b >> 8) & 0xFF;
		int bb = b & 0xFF;
		int alpha = Math.round(aa + (ba - aa) * f);
		int r = Math.round(ar + (br - ar) * f);
		int g = Math.round(ag + (bg - ag) * f);
		int bl = Math.round(ab + (bb - ab) * f);
		return (alpha << 24) | (r << 16) | (g << 8) | bl;
	}

	private static int withAlpha(int color, int alpha) {
		return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
	}

	private static double hash(int seed) {
		int x = seed * 0x9E3779B9;
		x ^= x >>> 16;
		x *= 0x85EBCA6B;
		x ^= x >>> 13;
		return (x & 0x7FFFFFFF) / (double) 0x7FFFFFFF;
	}

	private static MultiBufferSource.BufferSource batch() {
		if (batch == null) {
			buffer = new ByteBufferBuilder(BUFFER_BYTES);
			batch = MultiBufferSource.immediate(buffer);
		}
		return batch;
	}

	private static void discard() {
		if (batch != null) {
			try {
				batch.endBatch();
			} catch (RuntimeException | LinkageError ignored) {
				// The source is unusable either way.
			}
			batch = null;
		}
		if (buffer != null) {
			try {
				buffer.close();
			} catch (RuntimeException | LinkageError ignored) {
				// Nothing left to release.
			}
			buffer = null;
		}
	}
}
