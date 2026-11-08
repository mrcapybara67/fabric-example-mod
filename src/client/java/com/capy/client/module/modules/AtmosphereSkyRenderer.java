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
			sky.dome(theme);
			theme(sky, theme, now);
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

	/** Top, middle, horizon and below-horizon colours for a theme. */
	private static final int[][] PALETTES = {
			{0xFF06101C, 0xFF12304A, 0xFF6C9BB8, 0xFF102028},
			{0xFF180D22, 0xFF5B2731, 0xFFFF9860, 0xFF2A1512},
			{0xFF01030A, 0xFF08142F, 0xFF213765, 0xFF05070D},
			{0xFF010710, 0xFF062A32, 0xFF0B5B55, 0xFF020A0E},
			{0xFF20262F, 0xFF48515E, 0xFF8D98A6, 0xFF171B21},
			{0xFF01070F, 0xFF042B3E, 0xFF0B5D75, 0xFF010E16},
			{0xFF120306, 0xFF3B0C12, 0xFF962A20, 0xFF190405},
			{0xFF170A14, 0xFF4E2038, 0xFFE58DA8, 0xFF22101A},
			{0xFF010006, 0xFF0B061B, 0xFF2A1550, 0xFF030008},
			{0xFF01040B, 0xFF061B2A, 0xFF0B4A55, 0xFF02080D}
	};

	private static int[] palette(int theme) {
		return PALETTES[Math.max(0, Math.min(PALETTES.length - 1, theme))];
	}

	private static void theme(Sky sky, int theme, double now) {
		switch (theme) {
			case 1 -> goldenHour(sky, now);
			case 2 -> midnight(sky, now);
			case 3 -> aurora(sky, now);
			case 4 -> overcast(sky, now);
			case 5 -> deepOcean(sky, now);
			case 6 -> crimson(sky, now);
			case 7 -> sakura(sky, now);
			case 8 -> voidSky(sky, now);
			case 9 -> neon(sky, now);
			default -> gentle(sky, now);
		}
	}

	/** A calm blue sky: warm sun, a few long clouds, faint high stars. */
	private static void gentle(Sky sky, double now) {
		double drift = now * 0.23;
		sky.sun(41.0 + drift, 24.0, 5.2, 0xFFFFE3A8, 0x38FFD27A);
		for (int i = 0; i < 6; i++) {
			// Slightly different speeds per layer give the clouds parallax as
			// the player turns; one speed reads as a painted sheet.
			double yaw = i * 54 + now * (0.7 + i * 0.035);
			double pitch = 22 + (i % 3) * 7;
			sky.puff(yaw, pitch, 13 + i % 2 * 4, 2.2, 0x42FFFFFF);
		}
		for (int i = 0; i < 22; i++) {
			double twinkle = 0.45 + 0.55 * Math.sin(now * 0.9 + i * 1.7);
			sky.star(hash(i * 13 + 4) * 360, 18 + hash(i * 29 + 7) * 55,
					0.32, (int) (twinkle * 70), 0xFFE8F6FF);
		}
		// A slow, broad sheen along the horizon: the dome itself is cached, so
		// this is the part that keeps the gradient from looking printed on.
		double sheen = 0.5 + 0.5 * Math.sin(now * 0.18);
		sky.band(-30 + Math.sin(now * 0.05) * 24, 6, 72, 18,
				0x006C9BB8, withAlpha(0xFF8FC4E8, (int) (8 + sheen * 14)));
	}

	/** Golden Hour: a huge low sun, layered amber clouds and visible rays. */
	private static void goldenHour(Sky sky, double now) {
		double sunYaw = 66.0;
		double sunPitch = 10.0 + Math.sin(now * 0.08) * 0.8;
		sky.rays(sunYaw, sunPitch, 17, now * 0.06, 0x22FFC46A);
		sky.sun(sunYaw, sunPitch, 12.0, 0xFFFFD58A, 0x48FF9A4A);
		for (int i = 0; i < 10; i++) {
			double yaw = i * 36 + now * 1.0;
			double pitch = 16 + (i % 4) * 5;
			sky.puff(yaw, pitch, 16 + i % 3 * 5, 2.6 + i % 2, 0x38FFD5A0);
		}
		for (int i = 0; i < 6; i++) {
			double yaw = sunYaw + Math.sin(i * 2.1 + now * 0.04) * 40;
			double shimmer = 0.5 + 0.5 * Math.sin(now * 0.42 + i * 1.7);
			sky.band(yaw, 3, 9, 13, 0x001F0E08,
					withAlpha(0xFFFFB06A, (int) (20 + shimmer * 24)));
		}
	}

	/** Midnight: moon with craters, a dense star field and a shooting star. */
	private static void midnight(Sky sky, double now) {
		double moonYaw = -43.0;
		double moonPitch = 42.0;
		sky.sun(moonYaw, moonPitch, 7.2, 0xFFF2F6FF, 0x2A9FB8FF);
		sky.ellipse(moonYaw + 1.0, moonPitch + 1.2, 1.2, 1.8, 0x30223758, 5);
		sky.ellipse(moonYaw - 0.9, moonPitch - 1.4, 1.0, 1.2, 0x28223758, 4);
		// The phase shadow crosses the moon on a slow cycle, so the disc is not
		// always full. It is drawn after the craters and before the stars.
		double phase = (now % 240.0) / 240.0;
		sky.ellipse(moonYaw + (phase - 0.5) * 10.0, moonPitch, 6.4, 6.8,
				0xF0060A18, 32);
		for (int i = 0; i < 130; i++) {
			double yaw = hash(i * 17 + 3) * 360;
			double pitch = 4 + hash(i * 37 + 9) * 86;
			double twinkle = 0.35 + 0.65 * Math.sin(now * 1.3 + i * 2.1);
			int alpha = 30 + (int) (twinkle * 155);
			sky.star(yaw, pitch, i % 13 == 0 ? 0.48 : 0.24,
					alpha, i % 7 == 0 ? 0xFFBFD8FF : 0xFFFFFFFF);
		}
		// One shooting star every half minute, and only while it is crossing.
		double cycle = now % 30.0;
		if (cycle < 1.2) {
			double pass = cycle / 1.2;
			double sy = -80 + pass * 160;
			sky.streak(sy, 62 - pass * 18, sy + 12.6, 59 - pass * 18,
					0xE8FFFFFF, 0x00FFFFFF);
		}
	}

	/** Aurora: three living curtains behind a cold star field. */
	private static void aurora(Sky sky, double now) {
		for (int i = 0; i < 100; i++) {
			double twinkle = 0.4 + 0.6 * Math.sin(now * 1.1 + i * 1.3);
			sky.star(hash(i * 11 + 5) * 360, 8 + hash(i * 23 + 15) * 80,
					0.26, (int) (65 * twinkle), 0xFFC9FFF4);
		}
		sky.curtain(-80, 160, 17, 59, 22, now * 0.22, 0x8C4DFFC4, 0x006CFFB2);
		sky.curtain(-20, 160, 24, 70, 18, now * 0.17 + 1.7, 0x7A7195FF, 0x203A59FF);
		sky.curtain(-120, 140, 13, 48, 16, now * 0.26 + 3.1, 0x5C5CFFE0, 0x0044D6A4);
		for (int i = 0; i < 5; i++) {
			double yaw = i * 72 + now * 0.8;
			sky.puff(yaw, 10 + i % 2 * 4, 18, 1.8, 0x1A9BF2E0);
		}
	}

	/** Overcast: rolling storm layers and distant sheet lightning. */
	private static void overcast(Sky sky, double now) {
		for (int layer = 0; layer < 4; layer++) {
			double speed = 0.7 + layer * 0.35;
			for (int i = 0; i < 7; i++) {
				double yaw = i * 52 + now * speed + layer * 26;
				double pitch = 30 + layer * 6 + (i % 3) * 4;
				sky.puff(yaw, pitch, 19 - layer * 2, 3.2 - layer * 0.4,
						0x2C6D7885 + layer * 0x04000000);
			}
		}
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
	private static void deepOcean(Sky sky, double now) {
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
	private static void crimson(Sky sky, double now) {
		double pulse = 8.0 + Math.sin(now * 0.42) * 1.4;
		sky.sun(97.0, 16.0, pulse, 0xFFFF7A54, 0x48FF3A24);
		sky.ellipse(97.0, 16.0, pulse * 3.4, pulse * 3.4, 0x12FF2A18, 32);
		for (int i = 0; i < 9; i++) {
			double yaw = i * 45 + now * 0.6;
			double pitch = 17 + (i % 4) * 6;
			sky.puff(yaw, pitch, 20 - i % 3 * 3, 3.0, 0x38150608);
		}
		for (int i = 0; i < 40; i++) {
			double yaw = hash(i * 17 + 8) * 360 + Math.sin(now * 0.3 + i) * 3;
			double pitch = 10 + (hash(i * 29 + 5) + now * 0.02) % 1.0 * 55;
			double twinkle = 0.3 + 0.7 * Math.sin(now * 2.0 + i * 1.9);
			sky.star(yaw, pitch, 0.2 + hash(i) * 0.25, (int) (twinkle * 120), 0xFFFF8A42);
		}
	}

	/** Sakura: a rose dawn with soft clouds and drifting sky petals. */
	private static void sakura(Sky sky, double now) {
		sky.sun(23.0, 15.0, 6.4, 0xFFFFF0D2, 0x38FFB4C8);
		for (int i = 0; i < 8; i++) {
			double yaw = i * 47 + now * 1.0;
			double pitch = 21 + i % 3 * 8;
			sky.puff(yaw, pitch, 14 + i % 2 * 5, 2.2, 0x3CFFD8E5);
		}
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
	private static void voidSky(Sky sky, double now) {
		for (int i = 0; i < 110; i++) {
			double yaw = hash(i * 23 + 9) * 360;
			double pitch = 6 + hash(i * 37 + 11) * 82;
			double twinkle = 0.35 + 0.65 * Math.sin(now * 0.8 + i * 2.6);
			sky.star(yaw, pitch, i % 17 == 0 ? 0.55 : 0.22,
					(int) (twinkle * 145), i % 5 == 0 ? 0xFFD9B8FF : 0xFF9F7AD8);
		}
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
		// A slow rotating rift: three thin arcs whose phase never repeats
		// within a play session.
		for (int i = 0; i < 3; i++) {
			double angle = now * 0.11 + i * 2.1;
			sky.band(bx + Math.cos(angle) * 5.0, by + Math.sin(angle) * 4.0,
					4.5, 0.7, 0x003C1A68, 0x3A8A5AD8);
		}
	}

	/** Neon: a banded synthwave sun over a neon horizon grid. */
	private static void neon(Sky sky, double now) {
		double sunYaw = 0.0;
		double sunPitch = 22.0;
		double pulse = 0.5 + 0.5 * Math.sin(now * 0.45);

		// The horizon bloom sits behind the sun and the grid, so the lower sky
		// glows instead of ending in a hard colour seam.
		sky.ellipse(sunYaw, 5.0, 54.0, 8.0, 0x24FF3FBF, 10);
		sky.ellipse(sunYaw, 2.0, 30.0, 4.5, 0x30FF5AC8, 8);
		sky.sun(sunYaw, sunPitch, 16.0, 0xFFFF8ADA, 0x38FF48D0);

		// Opaque cutout bars, not translucent shading: they have to hide the
		// sun behind them. The slight phase offset makes the stripes breathe.
		for (int i = 0; i < 6; i++) {
			double cutPitch = sunPitch - 10.0 + i * 3.8
					+ Math.sin(now * 0.35 + i * 0.7) * 0.22;
			sky.band(sunYaw - 17.5, cutPitch, 17.5, 1.1,
					0xFF03101A, 0xFF061A24);
		}

		// Two cloud layers, one cyan and one magenta, drifting in opposite
		// directions across the upper half of the frame.
		for (int i = 0; i < 9; i++) {
			double yaw = i * 42 + now * 0.32;
			sky.puff(yaw, 35 + i % 3 * 5, 19 + i % 2 * 4, 1.4,
					i % 2 == 0 ? 0x2620E0D0 : 0x1EFF68C8);
		}
		for (int i = 0; i < 5; i++) {
			double yaw = -i * 61 - now * 0.22;
			sky.puff(yaw, 26 + i % 2 * 4, 13, 1.0, 0x18FF9AD8);
		}

		// Longitude lines run from below the horizon up into the lower sky.
		// They fade at the bottom and brighten towards the horizon, which is
		// what sells the plane receding away from the viewer.
		for (int i = 0; i < 18; i++) {
			double yaw = i * 20 + now * 0.18;
			sky.band(yaw, -14, 0.6, 17,
					0x0018F8E8, 0x5A18F8E8);
		}

		// Latitude lines are split into short segments by ring(); a single
		// flat quad across the horizon would cut through the dome.
		for (int i = 0; i < 7; i++) {
			double pitch = -12.0 + i * 2.1;
			int alpha = 0x16 + i * 0x07;
			sky.ring(pitch, 0.55, 32,
					0x0018C8D8, withAlpha(0xFF18C8D8, alpha));
		}
		sky.ring(-0.5, 0.9, 32, 0x0020E0D0, 0x7020E0D0);

		// A scan line sweeps up from the grid into the sun. The ring follows
		// the dome all the way round instead of ending at one side.
		double scan = (now * 0.045) % 1.0;
		sky.ring(-2.0 + scan * 24.0, 0.8, 32,
				0x0010F8E8, 0x3010F8E8);

		// A few high sparks keep the upper sky alive without becoming a star
		// field; the theme is still a neon horizon, not a night sky.
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
	private static final class Sky {
		private VertexConsumer vc;
		private PoseStack.Pose pose;

		void set(VertexConsumer vc, PoseStack.Pose pose) {
			this.vc = vc;
			this.pose = pose;
		}

		void dome(int theme) {
			ensureDome(theme);
			for (int i = 0; i < domeColors.length; i++) {
				int offset = i * 3;
				vc.addVertex(pose, domePositions[offset], domePositions[offset + 1],
						domePositions[offset + 2]).setColor(domeColors[i]);
			}
		}
		/** A vertical band between two yaw/pitch corners. */
		void band(double yaw, double pitch, double width, double height,
				int bottom, int top) {
			vertexAt(yaw - width, pitch, RADIUS * 0.985, bottom);
			vertexAt(yaw + width, pitch, RADIUS * 0.985, bottom);
			vertexAt(yaw + width, pitch + height, RADIUS * 0.985, top);
			vertexAt(yaw - width, pitch + height, RADIUS * 0.985, top);
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

		/** The sun or moon: a soft glow, then a bright core. */
		void sun(double yaw, double pitch, double radius, int core, int glow) {
			ellipse(yaw, pitch, radius * 2.4, radius * 2.4, withAlpha(glow, 20), 8);
			ellipse(yaw, pitch, radius * 1.55, radius * 1.55, withAlpha(glow, 40), 8);
			ellipse(yaw, pitch, radius, radius, core, 9);
		}

		/** Radiating wedges, drawn behind a sun. */
		void rays(double yaw, double pitch, int count, double phase, int color) {
			for (int i = 0; i < count; i++) {
				double a = i * Math.PI * 2 / count + phase;
				double spread = 2.0 + 1.3 * Math.sin(i * 1.7);
				double inner = 4.0;
				double outer = 19.0;
				vertexAt(yaw + Math.cos(a) * inner, pitch + Math.sin(a) * inner,
						RADIUS * 0.99, color);
				vertexAt(yaw + Math.cos(a + spread / outer) * outer,
						pitch + Math.sin(a + spread / outer) * outer,
						RADIUS * 0.99, withAlpha(color, 0));
				vertexAt(yaw + Math.cos(a - spread / outer) * outer,
						pitch + Math.sin(a - spread / outer) * outer,
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

		/** A small star with a faint cross. */
		void star(double yaw, double pitch, double size, int alpha, int color) {
			if (alpha <= 2) {
				return;
			}
			// One soft disc, with the centre carrying the twinkle and the rim
			// fading out. A cross of flat strips read as a diamond at small sizes.
			ellipse(yaw, pitch, size * 1.35, size * 1.35, withAlpha(color, alpha), 32);
		}

		/** A falling streak: a tapered line across the dome. */
		void streak(double yaw0, double pitch0, double yaw1, double pitch1,
				int head, int tail) {
			double width = 0.10;
			vertexAt(yaw0 - width, pitch0, RADIUS * 0.985, tail);
			vertexAt(yaw0 + width, pitch0, RADIUS * 0.985, tail);
			vertexAt(yaw1 + width, pitch1, RADIUS * 0.985, head);
			vertexAt(yaw1 - width, pitch1, RADIUS * 0.985, head);
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
		 * An ellipse on a plane facing the dome's centre. Drawing it in strips
		 * keeps the vertex format to quads, which the position-colour pipeline
		 * expects, while still producing a round silhouette.
		 */
		void ellipse(double yaw, double pitch, double halfWidth, double halfHeight,
				int color, int strips) {
			if (halfWidth <= 0 || halfHeight <= 0) {
				return;
			}
			double yawRad = Math.toRadians(yaw);
			double pitchRad = Math.toRadians(pitch);
			double cp = Math.cos(pitchRad);
			double dx = Math.sin(yawRad) * cp;
			double dy = Math.sin(pitchRad);
			double dz = Math.cos(yawRad) * cp;
			double rx = dz;
			double rz = -dx;
			double length = Math.hypot(rx, rz);
			if (length < 1.0e-5) {
				rx = 1.0;
				rz = 0.0;
				length = 1.0;
			}
			rx /= length;
			rz /= length;
			double ux = dy * rz;
			double uy = dz * rx - dx * rz;
			double uz = -dy * rx;
			double cx = dx * RADIUS * 0.994;
			double cy = dy * RADIUS * 0.994;
			double cz = dz * RADIUS * 0.994;
			double alpha = (color >>> 24) / 255.0;
			double sw = Math.sin(Math.toRadians(halfWidth));
			double sh = Math.sin(Math.toRadians(halfHeight));
			double rr = RADIUS * 0.994;
			// One centre and a ring of rim vertices: a radial fan instead of
			// stacked horizontal strips. The rim fades to zero, so the edge is
			// soft at any GUI scale and a star is a disc, not a diamond.
			int rim = Math.max(32, strips * 4);
			int centreColour = withAlpha(color, (int) (alpha * 235));
			for (int i = 0; i < rim; i++) {
				double a0 = Math.PI * 2.0 * i / rim;
				double a1 = Math.PI * 2.0 * (i + 1) / rim;
				double x0 = cx + (rx * Math.cos(a0) * sw + ux * Math.sin(a0) * sh) * rr;
				double y0 = cy + uy * Math.sin(a0) * sh * rr;
				double z0 = cz + (rz * Math.cos(a0) * sw + uz * Math.sin(a0) * sh) * rr;
				double x1 = cx + (rx * Math.cos(a1) * sw + ux * Math.sin(a1) * sh) * rr;
				double y1 = cy + uy * Math.sin(a1) * sh * rr;
				double z1 = cz + (rz * Math.cos(a1) * sw + uz * Math.sin(a1) * sh) * rr;
				vertex(cx, cy, cz, centreColour);
				vertex(x0, y0, z0, withAlpha(color, 0));
				vertex(x1, y1, z1, withAlpha(color, 0));
			}
		}
		private void vertexAt(double yawDegrees, double pitchDegrees, double radius, int color) {
			double yaw = Math.toRadians(yawDegrees);
			double pitch = Math.toRadians(pitchDegrees);
			double cp = Math.cos(pitch);
			vertex(Math.sin(yaw) * cp * radius, Math.sin(pitch) * radius,
					Math.cos(yaw) * cp * radius, color);
		}

		private void vertex(double x, double y, double z, int color) {
			vc.addVertex(pose, (float) x, (float) y, (float) z).setColor(color);
		}
	}

	private static void ensureDome(int theme) {
		if (theme == domeTheme && domePositions != null) {
			return;
		}
		int[] colours = palette(theme);
		int quads = SEGMENTS * RINGS;
		float[] positions = new float[quads * 4 * 3];
		int[] vertexColours = new int[quads * 4];
		double lower = -14;
		double upper = 90;
		int index = 0;
		for (int ring = 0; ring < RINGS; ring++) {
			double p0 = lower + (upper - lower) * ring / RINGS;
			double p1 = lower + (upper - lower) * (ring + 1) / RINGS;
			for (int segment = 0; segment < SEGMENTS; segment++) {
				double y0 = 360.0 * segment / SEGMENTS;
				double y1 = 360.0 * (segment + 1) / SEGMENTS;
				int c0 = domeColor(p0, y0, colours[0], colours[1], colours[2], colours[3]);
				int c1 = domeColor(p1, y1, colours[0], colours[1], colours[2], colours[3]);
				index = writeDomeVertex(positions, vertexColours, index, y0, p0, c0);
				index = writeDomeVertex(positions, vertexColours, index, y1, p0, c0);
				index = writeDomeVertex(positions, vertexColours, index, y1, p1, c1);
				index = writeDomeVertex(positions, vertexColours, index, y0, p1, c1);
			}
		}
		domePositions = positions;
		domeColors = vertexColours;
		domeTheme = theme;
	}

	private static int writeDomeVertex(float[] positions, int[] colours, int index,
			double yawDegrees, double pitchDegrees, int colour) {
		double yaw = Math.toRadians(yawDegrees);
		double pitch = Math.toRadians(pitchDegrees);
		double cp = Math.cos(pitch);
		int offset = index * 3;
		positions[offset] = (float) (Math.sin(yaw) * cp * RADIUS);
		positions[offset + 1] = (float) (Math.sin(pitch) * RADIUS);
		positions[offset + 2] = (float) (Math.cos(yaw) * cp * RADIUS);
		colours[index] = colour;
		return index + 1;
	}

	private static int domeColor(double pitch, double yaw, int top, int mid, int horizon, int below) {
		int colour;
		if (pitch >= 45) {
			colour = mix(mid, top, (float) Math.min(1.0, (pitch - 45) / 45.0));
		} else if (pitch >= 0) {
			colour = mix(horizon, mid, (float) (pitch / 45.0));
		} else if (pitch >= -14) {
			colour = mix(below, horizon, (float) ((pitch + 14) / 14.0));
		} else {
			colour = below;
		}
		// A sub-LSB dither breaks the remaining banding without a texture.
		float noise = (float) ((hash((int) (pitch * 37 + yaw * 11)) - 0.5) * 1.8);
		int alpha = (colour >>> 24) & 0xFF;
		int r = clamp8(Math.round(((colour >> 16) & 0xFF) + noise));
		int g = clamp8(Math.round(((colour >> 8) & 0xFF) + noise));
		int b = clamp8(Math.round((colour & 0xFF) + noise));
		return (alpha << 24) | (r << 16) | (g << 8) | b;
	}

	private static int clamp8(int value) {
		return Math.max(0, Math.min(255, value));
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
