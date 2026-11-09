package com.capy.client.gui;

/**
 * The single Glass design language.
 *
 * <p>There is no palette switching anymore. Every colour below is fixed: a
 * dark, cool-grey translucent body with a light-blue accent. Panels keep a
 * vertical value change so a large surface does not read as one flat block.</p>
 */
public final class UiTheme {
	private UiTheme() {
	}

	// ---- Backdrop ---------------------------------------------------------
	public static final int SCRIM = 0x8C0A0C0F;

	// ---- Surfaces (top -> bottom gradient pairs) -------------------------
	public static final int SHELL_TOP = 0xB33A4048;
	public static final int SHELL_BOTTOM = 0xB3282D33;
	public static final int PANEL_TOP = 0xB33F444C;
	public static final int PANEL_BOTTOM = 0xA82A2F35;
	public static final int PANEL_ALT = 0xB34A5058;
	public static final int CARD_TOP = 0xB341464E;
	public static final int CARD_BOTTOM = 0xA82E333A;
	public static final int CARD_HOVER_TOP = 0xC0525963;
	public static final int CARD_HOVER_BOTTOM = 0xB33A4048;
	public static final int WELL_TOP = 0xA824282E;
	public static final int WELL_BOTTOM = 0xA81A1E23;
	public static final int TRACK_TOP = 0xB3555C66;
	public static final int TRACK_BOTTOM = 0xA83B4149;

	// ---- Lines ------------------------------------------------------------
	public static final int BORDER = 0xB3C7D0DA;
	public static final int HIGHLIGHT = 0x47FFFFFF;
	public static final int SHADE = 0x3A000000;

	// ---- Accent (cool light blue; no pink anywhere) ----------------------
	public static final int ACCENT = 0xFF8FD3FF;
	public static final int ACCENT_2 = 0xFFBFE7FF;
	public static final int ACCENT_DEEP = 0xFF4A9BD1;
	public static final int ACCENT_GLOW = 0x338FD3FF;
	public static final int BLOSSOM = 0xFFD9F2FF;
	public static final int PALE_CHERRY = 0xFFEAF8FF;

	// ---- Text -------------------------------------------------------------
	public static final int TEXT = 0xFFF7FAFD;
	public static final int TEXT_2 = 0xFFDCE3EB;
	public static final int TEXT_3 = 0xFFAFB8C3;
	public static final int PLACEHOLDER = 0xFF86909C;
	public static final int ON_ACCENT = 0xFF101820;

	// ---- Shadows ----------------------------------------------------------
	public static final int SHADOW_WIDE = 0x4D000000;
	public static final int SHADOW_MID = 0x38000000;
	public static final int SHADOW_TIGHT = 0x44000000;

	// ---- Spacing scale (2 / 4 / 6 / 8 / 12 / 16 / 20 / 24) ---------------
	public static final int SP_1 = 2;
	public static final int SP_2 = 4;
	public static final int SP_3 = 6;
	public static final int SP_4 = 8;
	public static final int SP_5 = 12;
	public static final int SP_6 = 16;
	public static final int SP_7 = 20;
	public static final int SP_8 = 24;

	// ---- Radii ------------------------------------------------------------
	public static final int RADIUS = 10;
	public static final int RADIUS_LG = 14;
	public static final int RADIUS_XL = 20;
	public static final int RADIUS_PILL = 99;

	// ---- Layout -----------------------------------------------------------
	public static final int HEADER_H = 58;
	public static final int SIDEBAR_W = 190;
	public static final int CARD_H = 54;
	public static final int CARD_GAP = 8;
	public static final int TOGGLE_W = 40;
	public static final int TOGGLE_H = 22;
	public static final int SEARCH_W = 190;
	public static final int SEARCH_H = 30;
	public static final int CLOSE_BTN = 26;
	public static final int CATEGORY_H = 34;

	/** Kept for source compatibility with the single Glass surface. */
	public static boolean isGlass() {
		return true;
	}
}
