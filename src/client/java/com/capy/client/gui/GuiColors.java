package com.capy.client.gui;

import com.capy.client.module.ColorSetting;

/**
 * The fixed window accent.
 *
 * <p>GUI colour switching is gone: the window always uses the Glass light-blue
 * accent. The setting object remains only because the hidden legacy colour
 * studio still references it; {@link #sync()} no longer pushes anything into
 * the theme or the config.</p>
 */
public final class GuiColors {
	public static final int DEFAULT_WINDOW = UiTheme.ACCENT;

	private static final ColorSetting window = new ColorSetting("gui_accent", DEFAULT_WINDOW);

	private GuiColors() {
	}

	public static ColorSetting window() {
		return window;
	}

	public static int windowArgb() {
		return opaque(window.argb());
	}

	/**
	 * GUI accents are always opaque. A transparent accent would erase the
	 * radial disc and make the text on accent chips unreadable; an old config
	 * that still carries a translucent value is corrected here instead of
	 * being allowed to paint an invisible interface.
	 */
	private static int opaque(int argb) {
		return argb | 0xFF000000;
	}

	/** Retained for the legacy studio; the fixed accent needs no load step. */
	public static void load(int ignored) {
		window.set(DEFAULT_WINDOW);
	}

	/** GUI accent syncing is removed; the Glass accent never changes. */
	public static boolean sync() {
		return false;
	}
}
