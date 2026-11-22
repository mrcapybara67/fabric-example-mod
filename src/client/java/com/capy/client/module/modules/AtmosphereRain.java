package com.capy.client.module.modules;

/**
 * The colour each theme gives the falling rain.
 *
 * <p>Vanilla draws rain with a white vertex colour whose alpha comes from the
 * current rain intensity. That makes every storm grey, and a dark themed sky
 * can swallow it. The weather hook replaces the white with the theme's rain
 * colour and lifts the alpha a little, while leaving the vanilla geometry and
 * the snow pass untouched. No extra rain layer is spawned: the drops the game
 * already draws simply become part of the theme.</p>
 */
public final class AtmosphereRain {
	private static final int[] COLORS = {
			0xFFB8E6FF, // default: pale ice blue
			0xFFFFC46A, // golden hour: warm amber
			0xFF708CFF, // midnight: deep blue
			0xFF58FFC8, // aurora: green-cyan
			0xFFD8DEE8, // overcast: cold silver
			0xFF40E8FF, // deep ocean: bright cyan
			0xFFFF6A5A, // crimson: hot coral
			0xFFFFB8D8, // sakura: blossom pink
			0xFFC08CFF, // void: violet
			0xFFFF4FE0  // neon: magenta
	};

	private AtmosphereRain() {
	}

	/** The theme's rain colour as an RGB value; alpha belongs to the drop. */
	public static int rainColor(int theme) {
		return COLORS[Math.floorMod(theme, COLORS.length)] & 0x00FFFFFF;
	}

	/**
	 * Rain passes {@code 1.0} to the column renderer; snow passes {@code 0.8}.
	 * Comparing the factor is how the tint reaches rain without recolouring
	 * snow in the same biome pass.
	 */
	public static boolean isRain(float distanceFactor) {
		return distanceFactor >= 0.99f;
	}

	/**
	 * Recolours one vanilla rain vertex and makes it easier to see.
	 *
	 * <p>The source colour is {@code ARGB.white(alpha)}: only the alpha is
	 * meaningful. The returned alpha is boosted, but never past opaque, so a
	 * light drizzle stays lighter than a storm.</p>
	 */
	public static int tint(int vertexColor, int theme) {
		int alpha = (vertexColor >>> 24) & 0xFF;
		if (alpha == 0) {
			return vertexColor;
		}
		int boosted = Math.min(255, Math.round(alpha * 1.45f) + 22);
		return (boosted << 24) | rainColor(theme);
	}
}
