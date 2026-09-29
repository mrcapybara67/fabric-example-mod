package com.griefer.client.gui;

/**
 * Design-system tokens for the Griefer GUI.
 *
 * One place for every color, spacing and layout constant so the whole
 * interface stays visually consistent. The palette is a calm graphite base
 * with a single teal accent — flat surfaces, thin hairlines, no neon, no
 * gradients. Components never hardcode colors; they read tokens from here.
 */
public final class UiTheme {
	private UiTheme() {
	}

	// ---- Surfaces (low -> high elevation) -------------------------------
	public static final int SCRIM = 0x6005080C;       // dim behind the window
	public static final int SURFACE_0 = 0xF0101318;   // window body
	public static final int SURFACE_1 = 0xFF161B22;   // sidebar / cards
	public static final int SURFACE_2 = 0xFF1D242E;   // hover / controls
	public static final int SURFACE_3 = 0xFF26303C;   // pressed / knobs

	// ---- Hairlines -------------------------------------------------------
	public static final int LINE = 0xFF232B36;
	public static final int LINE_HI = 0xFF33404F;

	// ---- Accent ----------------------------------------------------------
	public static final int ACCENT = 0xFF4EC9B0;

	// ---- Text ------------------------------------------------------------
	public static final int TEXT = 0xFFE6EBF0;
	public static final int TEXT_SUB = 0xFF98A2AE;
	public static final int TEXT_FAINT = 0xFF5C6672;

	// ---- States ----------------------------------------------------------
	public static final int DANGER = 0xFFE06C75;
	public static final int SHADOW = 0x44000000;

	// ---- Geometry --------------------------------------------------------
	public static final int RADIUS = 4;      // corner radius (cards/controls)
	public static final int RADIUS_SM = 2;   // toggle track, scrollbar

	// ---- Spacing scale (px) ----------------------------------------------
	public static final int SP_1 = 2;
	public static final int SP_2 = 4;
	public static final int SP_3 = 6;
	public static final int SP_4 = 8;
	public static final int SP_5 = 12;

	// ---- Layout ----------------------------------------------------------
	public static final int SIDEBAR_W = 106;
	public static final int CONTENT_PAD = 14;
	public static final int CARD_GAP = 6;
	public static final int CARD_H = 26;     // collapsed module card height
	public static final int TOGGLE_W = 22;
	public static final int TOGGLE_H = 12;
}
