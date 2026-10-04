package com.griefer.client.gui;

/**
 * Design-system tokens for the Griefer GUI.
 *
 * The visual language is "liquid glass": layered translucent surfaces that let
 * the game show through, a bright specular rim along the top edge, a darker
 * refracting edge at the bottom, and soft wide shadows that make each panel
 * float above whatever is behind it.
 *
 * Every glass colour therefore comes in pairs — {@code *_TOP} and
 * {@code *_BOTTOM} — which {@link Ui#glassFill} interpolates row by row to
 * produce the vertical light gradient that sells the material. Surfaces are
 * never flat single colours, and translucent fills are never stacked on top of
 * one another where they would compound into mud: each layer's alpha is chosen
 * so the stack still reads as clean, bright glass.
 */
public final class UiTheme {
	private UiTheme() {
	}

	// ---- Backdrop ---------------------------------------------------------
	/** Dims and desaturates the world behind the GUI so glass has something to sit on. */
	public static final int SCRIM = 0x5E05070B;
	/** Faint accent bloom under the window, as if the glass is lit from within. */
	public static final int ACCENT_GLOW = 0x2E2FE6C4;

	// ---- Glass surfaces (top -> bottom gradient pairs) --------------------
	/** Main window shell. */
	public static final int SHELL_TOP = 0xC4121822;
	public static final int SHELL_BOTTOM = 0xD6090D13;
	/** Inner panels: sidebar, search, settings well. */
	public static final int PANEL_TOP = 0x8E1B222D;
	public static final int PANEL_BOTTOM = 0xA610161F;
	/** Module cards — the most transparent layer, so the world reads through. */
	public static final int CARD_TOP = 0x8E202934;
	public static final int CARD_BOTTOM = 0xA6161D28;
	/** Resting/hover elevation for cards. */
	public static final int CARD_HOVER_TOP = 0xA0263140;
	public static final int CARD_HOVER_BOTTOM = 0xB81A2230;
	/** Controls sitting inside a card: slider track, toggle track. */
	public static final int TRACK_TOP = 0x99151B25;
	public static final int TRACK_BOTTOM = 0xB00D1219;

	// ---- Glass edges (the "specular rim") ---------------------------------
	/** Bright rim along the top edge, as if light catches the top bevel. */
	public static final int EDGE_TOP = 0x66FFFFFF;
	/** Faint rim along the bottom edge — refraction, not a highlight. */
	public static final int EDGE_BOTTOM = 0x16FFFFFF;
	/** Inner highlight just inside the top edge (the pane has thickness). */
	public static final int INNER_TOP = 0x2EFFFFFF;
	/** Inner shade just inside the bottom edge. */
	public static final int INNER_BOTTOM = 0x3A000000;
	/** Broad diagonal light that slowly sweeps across the shell. */
	public static final int SHEEN = 0x1CFFFFFF;

	// ---- Shadows ----------------------------------------------------------
	public static final int SHADOW_WIDE = 0x50000000;
	public static final int SHADOW_MID = 0x38000000;
	public static final int SHADOW_TIGHT = 0x44000000;
	/** Accent-tinted glow drawn under active/enabled elements. */
	public static final int GLOW_ACCENT = 0x4D5FE3C8;

	// ---- Accent (used strategically: active, enabled, focus) -------------
	public static final int ACCENT = 0xFF5FE3C8;
	public static final int ACCENT_30 = 0x4D5FE3C8;
	public static final int ACCENT_DIM = 0xFF43B49E;

	// ---- Text -------------------------------------------------------------
	public static final int TEXT = 0xFFF3F7FB;
	public static final int TEXT_2 = 0xFFA7B2BF;
	public static final int TEXT_3 = 0xFF6D7987;
	public static final int PLACEHOLDER = 0xFF5B6674;
	public static final int HINT = 0xFF47515E;
	public static final int ON_ACCENT = 0xFF06231D;

	// ---- Spacing scale (4 / 8 / 12 / 16; 2 = micro gap) -----------------
	public static final int SP_1 = 2;
	public static final int SP_2 = 4;
	public static final int SP_3 = 8;
	public static final int SP_4 = 12;
	public static final int SP_5 = 16;
	// ---- Radii (rounder than a typical flat GUI — glass has no hard corners)
	public static final int RADIUS = 8;      // controls, cards
	public static final int RADIUS_LG = 16;  // inner panels
	public static final int RADIUS_XL = 20;  // window shell
	public static final int RADIUS_PILL = 99; // clamped by Ui.roundRect

	// ---- Layout ------------------------------------------------------------
	public static final int HEADER_H = 56;
	public static final int SIDEBAR_W = 172;
	public static final int CARD_H = 48;
	public static final int CARD_GAP = 8;
	public static final int TOGGLE_W = 40;
	public static final int TOGGLE_H = 22;
	public static final int SEARCH_W = 176;
	public static final int SEARCH_H = 30;
	public static final int CLOSE_BTN = 26;
}
