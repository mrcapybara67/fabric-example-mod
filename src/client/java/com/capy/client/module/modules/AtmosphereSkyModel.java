package com.capy.client.module.modules;

/**
 * The shared light model for Atmosphere.
 *
 * <p>The sky is not a fixed picture per theme. The sun has one direction,
 * derived from the same time of day the world lighting uses, and every layer
 * reads it: the dome gradient, the solar halo, the clouds, the moon, the fog
 * and the particles. A theme changes the composition of the air around that
 * light, not where the light is.</p>
 *
 * <p>This is a CPU approximation, not a physically exact atmosphere. It uses
 * the two pieces that matter most for the look: a Rayleigh-like blue gradient
 * that is strongest away from the sun, and a Mie-like forward glow around the
 * sun and along the horizon. Keeping it in Java means the module stays
 * self-contained and does not require Iris or a post-processing chain.</p>
 */
public final class AtmosphereSkyModel {
	/** Tilt of the solar path away from the zenith, in radians. */
	private static final double PATH_TILT = 0.34;

	/**
	 * Sun position and the three lighting weights used by the renderer.
	 *
	 * <p>{@code yaw} and {@code pitch} are degrees in the same convention as
	 * the dome: yaw 0 faces +Z, yaw 90 faces +X. {@code elevation} is the
	 * normalised vertical component of the sun direction, so it is 1 at the
	 * zenith; {@code daylight}, {@code twilight} and {@code night} are already
	 * shaped for blending and are not raw angles.</p>
	 */
	public record Sun(float yaw, float pitch, float elevation,
			float daylight, float twilight, float night) {
		public double yawRadians() {
			return Math.toRadians(yaw);
		}

		public double pitchRadians() {
			return Math.toRadians(pitch);
		}
	}

	/**
	 * One theme's art direction. The night and day pairs let a theme keep its
	 * identity while still responding to a changed time of day; the sunset
	 * colour is the warm band that appears when the sun crosses the horizon.
	 */
	private record Palette(int nightTop, int nightMid, int nightHorizon, int nightBelow,
			int dayTop, int dayMid, int dayHorizon, int dayBelow,
			int sunset, int sun, int glow, int cloudLit, int cloudShade) {
	}

	private static final Palette[] PALETTES = {
			// Default: a real blue day, a cold night, and a warm low-sun horizon.
			new Palette(0xFF02050C, 0xFF07152A, 0xFF1A2C46, 0xFF03060B,
					0xFF2A6FB6, 0xFF6DA4D8, 0xFFC9DDF2, 0xFF3A4A5C,
					0xFFFFA35C, 0xFFFFF3CE, 0xFFFFB35C, 0x78FFFFFF, 0x5097A6B8),
			// Golden Hour.
			new Palette(0xFF160B1D, 0xFF3A1E2E, 0xFF7E4638, 0xFF140A0E,
					0xFF2C3E72, 0xFF8D5F6D, 0xFFFFB06B, 0xFF3A2218,
					0xFFFF8A3C, 0xFFFFC27A, 0xFFFF9A4A, 0x80FFD5A0, 0x506B3B32),
			// Midnight.
			new Palette(0xFF01030A, 0xFF08142F, 0xFF213765, 0xFF05070D,
					0xFF071226, 0xFF102A48, 0xFF2A4A72, 0xFF050A12,
					0xFF6B4A6A, 0xFFDCE8FF, 0xFF7080B8, 0x70C8D8F0, 0x50303848),
			// Aurora.
			new Palette(0xFF01050B, 0xFF041E2A, 0xFF0A4A48, 0xFF010408,
					0xFF0A2530, 0xFF1C5058, 0xFF7AC8C0, 0xFF041014,
					0xFF5CFFD8, 0xFFD8FFF4, 0xFF4CFFD0, 0x688AD8D0, 0x481A3838),
			// Overcast.
			new Palette(0xFF12161C, 0xFF232A33, 0xFF4A5562, 0xFF0C0F14,
					0xFF586674, 0xFF8A96A2, 0xFFC4CDD5, 0xFF3A424A,
					0xFFB08A78, 0xFFD8D8D8, 0xFFA8B0B8, 0x88E0E6EC, 0x705C6670),
			// Deep Ocean: the sky is the underside of the water surface.
			new Palette(0xFF01070F, 0xFF042B3E, 0xFF0B5D75, 0xFF010E16,
					0xFF06384E, 0xFF0C6A86, 0xFF8AD8E8, 0xFF021820,
					0xFFFFB070, 0xFFD8FFFF, 0xFF70E0FF, 0x68B8F4FF, 0x501A5868),
			// Crimson.
			new Palette(0xFF120306, 0xFF3B0C12, 0xFF962A20, 0xFF190405,
					0xFF2A0C12, 0xFF6A1A1A, 0xFFD85838, 0xFF260808,
					0xFFFF5A2A, 0xFFFF8A5A, 0xFFFF4A20, 0x68FFB090, 0x504A1010),
			// Sakura.
			new Palette(0xFF170A14, 0xFF4E2038, 0xFFE58DA8, 0xFF22101A,
					0xFF5A3A66, 0xFFB86A86, 0xFFFFD8E4, 0xFF4A2030,
					0xFFFFB0C8, 0xFFFFF0F4, 0xFFFFA8C8, 0x70FFF4FA, 0x50A85A78),
			// Void.
			new Palette(0xFF010006, 0xFF0B061B, 0xFF2A1550, 0xFF030008,
					0xFF08040C, 0xFF1A1030, 0xFF4A2A70, 0xFF050008,
					0xFF8A4AD8, 0xFFE8D8FF, 0xFF6A3AC8, 0x506A4A90, 0x401A0A28),
			// Neon.
			new Palette(0xFF01040B, 0xFF061B2A, 0xFF0B4A55, 0xFF02080D,
					0xFF0B1A38, 0xFF2A2A6A, 0xFFB04AA8, 0xFF0A0A1A,
					0xFFFF3FBF, 0xFFFF8ADA, 0xFFFF3FBF, 0x60FF68C8, 0x4820E0D0)
	};

	private AtmosphereSkyModel() {
	}

	/**
	 * The current sun. If the module's clock is off, the server's day time is
	 * converted into the module's friendly convention (0 = midnight,
	 * 0.25 = sunrise, 0.5 = noon, 0.75 = sunset).
	 */
	public static Sun sun(float override, long dayTime) {
		float time = override >= 0f ? AtmosphereMath.wrap(override) : vanillaTime(dayTime);
		double angle = (time - 0.25) * Math.PI * 2.0;
		double x = Math.cos(angle);
		double y = Math.sin(angle) * Math.cos(PATH_TILT);
		double z = -Math.sin(angle) * Math.sin(PATH_TILT);
		double length = Math.sqrt(x * x + y * y + z * z);
		x /= length;
		y /= length;
		z /= length;

		float yaw = (float) Math.toDegrees(Math.atan2(x, z));
		float pitch = (float) Math.toDegrees(Math.asin(y));
		float sunlight = smoothstep(-0.06f, 0.16f, (float) y);
		float night = 1f - smoothstep(-0.20f, 0.03f, (float) y);
		float twilight = (float) Math.exp(-(y * y) / (0.075 * 0.075));
		return new Sun(yaw, pitch, (float) y, sunlight, twilight, night);
	}

	/** The sky colour for one dome vertex. */
	public static int skyColor(int theme, double yaw, double pitch, Sun sun) {
		Palette p = palette(theme);
		float day = sun.daylight();
		int top = mix(p.nightTop, p.dayTop, day);
		int middle = mix(p.nightMid, p.dayMid, day);
		int horizon = mix(p.nightHorizon, p.dayHorizon, day);
		int below = mix(p.nightBelow, p.dayBelow, day);
		int colour;
		if (pitch >= 45.0) {
			colour = mix(middle, top, (float) Math.min(1.0, (pitch - 45.0) / 45.0));
		} else if (pitch >= 0.0) {
			colour = mix(horizon, middle, (float) (pitch / 45.0));
		} else if (pitch >= -14.0) {
			colour = mix(below, horizon, (float) ((pitch + 14.0) / 14.0));
		} else {
			colour = below;
		}

		double yawRadians = Math.toRadians(yaw);
		double pitchRadians = Math.toRadians(pitch);
		double sunYawRadians = sun.yawRadians();
		double sunPitchRadians = sun.pitchRadians();
		double cosPitch = Math.cos(pitchRadians);
		double sunCosPitch = Math.cos(sunPitchRadians);
		double vx = Math.sin(yawRadians) * cosPitch;
		double vy = Math.sin(pitchRadians);
		double vz = Math.cos(yawRadians) * cosPitch;
		double sx = Math.sin(sunYawRadians) * sunCosPitch;
		double sy = Math.sin(sunPitchRadians);
		double sz = Math.cos(sunYawRadians) * sunCosPitch;
		double dot = vx * sx + vy * sy + vz * sz;
		double facing = horizontalFacing(yaw, sun.yaw());
		double horizonBand = 1.0 - smoothstep(0.0, 0.55, Math.abs(vy));

		// A warm crescent around the sun's side of the horizon at dawn/dusk.
		float warm = (float) (sun.twilight() * horizonBand
				* (0.30 + 0.70 * Math.max(0.0, facing)));
		colour = mix(colour, p.sunset, warm * 0.72f);

		// The anti-solar side cools first as the earth's shadow climbs.
		float shadow = (float) (sun.twilight() * horizonBand
				* Math.max(0.0, -facing) * 0.24);
		colour = mix(colour, p.nightHorizon, shadow);

		// Broad Mie-like forward scattering and a tighter glow around the disc.
		float broad = (float) (Math.pow(Math.max(0.0, dot), 8.0)
				* (0.18 + 0.82 * day));
		colour = add(colour, p.glow, broad * 0.50f);
		float tight = (float) (Math.pow(Math.max(0.0, dot), 64.0)
				* (0.30 + 0.70 * day));
		colour = add(colour, p.sun, tight * 0.28f);

		// The player's sky tint is an atmosphere tint, not a replacement for
		// the theme. Keeping it at 18% means the picker is visible without
		// flattening every theme into one colour.
		float tint = Math.max(0f, Math.min(1f, AtmosphereState.skyHot)) * 0.18f;
		int picked = 0xFF000000
				| (Math.round(AtmosphereState.skyR * 255f) << 16)
				| (Math.round(AtmosphereState.skyG * 255f) << 8)
				| Math.round(AtmosphereState.skyB * 255f);
		colour = mix(colour, picked, tint);

		return dither(colour, yaw, pitch);
	}

	/** The horizon colour a camera looking in {@code viewYaw} should see. */
	public static int horizonColor(int theme, double viewYaw, Sun sun) {
		return skyColor(theme, viewYaw, 0.0, sun);
	}

	/**
	 * The fog target for the current camera direction. Fog is never a separate
	 * colour from the sky: it starts with the theme's air and picks up the
	 * horizon, then warms slightly when looking into the sun.
	 */
	public static int fogColor(int theme, double viewYaw, Sun sun) {
		Palette p = palette(theme);
		int themed = 0xFF000000
				| (Math.round(AtmosphereState.fogR * 255f) << 16)
				| (Math.round(AtmosphereState.fogG * 255f) << 8)
				| Math.round(AtmosphereState.fogB * 255f);
		int horizon = horizonColor(theme, viewYaw, sun);
		int target = mix(themed, horizon, 0.56f);
		double facing = Math.max(0.0, horizontalFacing(viewYaw, sun.yaw()));
		float glow = (float) (sun.twilight() * Math.pow(facing, 4.0));
		return mix(target, p.sunset, glow * 0.32f);
	}

	public static int sunColor(int theme) {
		return palette(theme).sun;
	}

	public static int sunGlow(int theme) {
		return palette(theme).glow;
	}

	public static int sunsetColor(int theme) {
		return palette(theme).sunset;
	}

	public static int cloudLit(int theme, Sun sun) {
		return dim(palette(theme).cloudLit, 0.35f + 0.65f * sun.daylight());
	}

	public static int cloudShade(int theme, Sun sun) {
		return dim(palette(theme).cloudShade, 0.24f + 0.52f * sun.daylight());
	}

	/** 1 at night, 0 in daylight, with a soft astronomical twilight. */
	public static float starVisibility(Sun sun) {
		return 1f - smoothstep(-0.18f, 0.05f, sun.elevation());
	}

	/** Minecraft's eight-phase moon cycle, driven by the world day count. */
	public static float moonPhase(long dayTime) {
		long day = Math.floorDiv(dayTime, 24000L);
		return Math.floorMod(day, 8L) / 8f;
	}

	private static Palette palette(int theme) {
		return PALETTES[Math.max(0, Math.min(PALETTES.length - 1, theme))];
	}

	private static float vanillaTime(long dayTime) {
		long wrapped = Math.floorMod(dayTime, 24000L);
		return AtmosphereMath.wrap(wrapped / 24000f + 0.25f);
	}

	private static double horizontalFacing(double viewYaw, double sunYaw) {
		return Math.cos(Math.toRadians(viewYaw - sunYaw));
	}

	private static int mix(int a, int b, float t) {
		float f = Math.max(0f, Math.min(1f, t));
		int aa = (a >>> 24) & 0xFF;
		int ar = (a >> 16) & 0xFF;
		int ag = (a >> 8) & 0xFF;
		int ab = a & 0xFF;
		int ba = (b >>> 24) & 0xFF;
		int br = (b >> 16) & 0xFF;
		int bg = (b >> 8) & 0xFF;
		int bb = b & 0xFF;
		return (Math.round(aa + (ba - aa) * f) << 24)
				| (Math.round(ar + (br - ar) * f) << 16)
				| (Math.round(ag + (bg - ag) * f) << 8)
				| Math.round(ab + (bb - ab) * f);
	}

	private static int add(int base, int light, float strength) {
		float f = Math.max(0f, Math.min(1f, strength));
		int r = Math.min(255, ((base >> 16) & 0xFF) + Math.round(((light >> 16) & 0xFF) * f));
		int g = Math.min(255, ((base >> 8) & 0xFF) + Math.round(((light >> 8) & 0xFF) * f));
		int b = Math.min(255, (base & 0xFF) + Math.round((light & 0xFF) * f));
		return (base & 0xFF000000) | (r << 16) | (g << 8) | b;
	}

	private static int dim(int color, float factor) {
		float f = Math.max(0f, Math.min(1f, factor));
		int a = (color >>> 24) & 0xFF;
		int r = Math.round(((color >> 16) & 0xFF) * f);
		int g = Math.round(((color >> 8) & 0xFF) * f);
		int b = Math.round((color & 0xFF) * f);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	private static int dither(int color, double yaw, double pitch) {
		int noise = (int) Math.round((hash((int) (pitch * 37 + yaw * 11)) - 0.5) * 1.6);
		int a = (color >>> 24) & 0xFF;
		int r = clamp8(((color >> 16) & 0xFF) + noise);
		int g = clamp8(((color >> 8) & 0xFF) + noise);
		int b = clamp8((color & 0xFF) + noise);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	private static int clamp8(int value) {
		return Math.max(0, Math.min(255, value));
	}

	private static float smoothstep(float edge0, float edge1, float value) {
		if (edge1 <= edge0) {
			return value < edge0 ? 0f : 1f;
		}
		float t = Math.max(0f, Math.min(1f, (value - edge0) / (edge1 - edge0)));
		return t * t * (3f - 2f * t);
	}

	private static double smoothstep(double edge0, double edge1, double value) {
		if (edge1 <= edge0) {
			return value < edge0 ? 0.0 : 1.0;
		}
		double t = Math.max(0.0, Math.min(1.0, (value - edge0) / (edge1 - edge0)));
		return t * t * (3.0 - 2.0 * t);
	}

	private static double hash(int seed) {
		int x = seed * 0x9E3779B9;
		x ^= x >>> 16;
		x *= 0x85EBCA6B;
		x ^= x >>> 13;
		return (x & 0x7FFFFFFF) / (double) 0x7FFFFFFF;
	}
}
