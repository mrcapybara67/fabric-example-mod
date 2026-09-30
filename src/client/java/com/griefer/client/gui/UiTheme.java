package com.griefer.client.gui;

/**
 * Design-system tokens for the Griefer GUI.
 *
 * A sophisticated dark palette (near-black blue-gray surfaces), one
 * disciplined teal accent used only for active/selected/enabled states,
 * a 4-based spacing scale, and consistent radii. Alpha-carrying variants
 * of the accent (ACCENT_10/ACCENT_18) exist so tinted surfaces never
 * need per-frame color math.
 */
public final class UiTheme {
	private UiTheme() {
	}

	// ---- Surfaces (dark -> elevated) ------------------------------------
	public static final int SCRIM = 0x52060A0F;      // dim behind the window
	public static final int BG = 0xF30D1014;         // window shell
	public static final int SURFACE = 0xFF151A21;    // search field / slider track bg
	public static final int SURFACE_2 = 0xFF1B212A;  // card base
	public static final int SURFACE_3 = 0xFF232B36;  // card hover / pressed / toggle track

	// ---- Lines ------------------------------------------------------------
	public static final int LINE = 0xFF20262F;       // separators on BG
	public static final int HAIRLINE = 0x24FFFFFF;   // subtle white borders

	// ---- Accent (used strategically: active, enabled, focus) -------------
	public static final int ACCENT = 0xFF4EC9B0;
	public static final int ACCENT_10 = 0x1A4EC9B0;  // 10% tinted surface
	public static final int ACCENT_18 = 0x2E4EC9B0;  // 18% focus ring
	public static final int ACCENT_DIM = 0xFF3A8F7F;

	// ---- Text --------------------------------------------------------------
	public static final int TEXT = 0xFFEDF1F5;
	public static final int TEXT_2 = 0xFF9AA5B1;
	public static final int TEXT_3 = 0xFF626D7A;
	public static final int PLACEHOLDER = 0xFF525D6B;
	public static final int HINT = 0xFF3C454F;      // sidebar foot hint
	public static final int ON_ACCENT = 0xFF0B2E27; // text on the accent chip

	// ---- Shadows -----------------------------------------------------------
	public static final int SHADOW = 0x6B000000;

	// ---- Spacing scale (4 / 8 / 12 / 16 / 20 / 24; 2 = micro gap) ----------
	public static final int SP_1 = 2;
	public static final int SP_2 = 4;
	public static final int SP_3 = 8;
	public static final int SP_4 = 12;
	public static final int SP_5 = 16;
	public static final int SP_6 = 20;
	public static final int SP_7 = 24;

	// ---- Radii -------------------------------------------------------------
	public static final int RADIUS = 4;    // cards, inputs, nav pills
	public static final int RADIUS_LG = 6; // window shell
	public static final int RADIUS_PILL = 99; // clamped by Ui.roundRect

	// ---- Layout ------------------------------------------------------------
	public static final int HEADER_H = 44;
	public static final int SIDEBAR_W = 132;
	public static final int CARD_H = 34;
	public static final int CARD_GAP = 4;
	public static final int TOGGLE_W = 30;
	public static final int TOGGLE_H = 14;
	public static final int SEARCH_W = 150;
	public static final int SEARCH_H = 24;
}
