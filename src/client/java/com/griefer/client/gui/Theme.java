package com.griefer.client.gui;

/**
 * Palette + drawing helpers for the Griefer ClickGUI.
 * Deliberately unlike Meteor: terminal-style black-on-slate with a single
 * electric blue accent, chamfered (clipped corner) panels and HUD-line text.
 */
public final class Theme {
	// Base surfaces
	public static final int BG = 0xC811141A; // translucent near-black
	public static final int BG_RAISED = 0xD21B2029; // raised surface
	public static int accentOf(int argb) {
		return argb;
	}

	// Accent (electric blue)
	public static final int ACCENT = 0xFF59C1FF;
	public static final int ACCENT_DIM = 0xFF2E6E96;
	public static final int ACCENT_DARK = 0xFF173248;

	// Text
	public static final int TEXT = 0xFFE8F0FA;
	public static final int TEXT_DIM = 0xFF8B97A6;
	public static final int TEXT_DISABLED = 0xFF555F6B;

	// Lines
	public static final int EDGE = 0xFF2A3340;
	public static final int EDGE_HI = 0xFF3D4A5C;
	public static final int SCAN = 0xFF16202B; // subtle scanline striping

	// States
	public static final int GOOD = 0xFF59FFB2;
	public static final int WARN = 0xFFFFC24B;
	public static final int BAD = 0xFFFF5C6E;
	public static final int SHADOW = 0x66000000;
	public static final int LED_GLOW = 0x5059FFB2;

	private Theme() {
	}
}
