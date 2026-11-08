package com.capy.client.gui;

import com.capy.client.config.ClientConfig;
import com.capy.client.module.ColorSetting;

/**
 * The user-chosen window accent.
 *
 * It is stored as a plain {@link ColorSetting} so the existing colour picker
 * can edit it. The setting is not attached to a module — it belongs to the
 * client itself — and {@link #sync()} is what pushes a change out to
 * {@link UiTheme} and to the config object.
 */
public final class GuiColors {
	// The window default is the Sakura palette accent on purpose: while the
	// accent still equals the palette, switching themes is allowed to carry the
	// accent with it. A default that differed would look customised from the
	// first launch and the theme picker would leave it behind.
	public static final int DEFAULT_WINDOW = 0xFFFF74A8;

	private static final ColorSetting window = new ColorSetting("gui_accent", DEFAULT_WINDOW);

	/** Last value pushed out, so a frame only reacts to a real change. */
	private static int appliedWindow = DEFAULT_WINDOW;

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

	/** Applies the persisted value once, at startup. */
	public static void load(int windowArgb) {
		window.set(opaque(windowArgb));
		appliedWindow = window.argb();
		// Write the sanitised value back, so an old translucent accent is not
		// saved again on the next close and has to be corrected every launch.
		ClientConfig.get().setGuiAccent(appliedWindow);
		UiTheme.setAccent(appliedWindow);
	}

	/**
	 * Pushes the current value into the live theme and the config. Returns true
	 * when something changed this frame.
	 */
	public static boolean sync() {
		int nowWindow = windowArgb();
		if (nowWindow == appliedWindow) {
			return false;
		}
		appliedWindow = nowWindow;
		UiTheme.setAccent(nowWindow);
		ClientConfig.get().setGuiAccent(nowWindow);
		return true;
	}
}
