package com.capy.client.gui;

import com.capy.client.config.ClientConfig;
import com.capy.client.module.ColorSetting;

/**
 * The two user-chosen GUI accents.
 *
 * The window style and the radial ring deliberately keep separate accents:
 * someone who wants a green window may still want the ring to stay pink, and
 * switching styles must never silently recolour the other one.
 *
 * Both are stored as plain {@link ColorSetting}s so the existing colour picker
 * can edit them without a second, near-identical control. The settings are not
 * attached to a module — they belong to the client itself — and
 * {@link #sync()} is what pushes a change out to {@link UiTheme} and to the
 * config object.
 */
public final class GuiColors {
	// The window default is the Sakura palette accent on purpose: while the
	// accent still equals the palette, switching themes is allowed to carry the
	// accent with it. A default that differed would look customised from the
	// first launch and the theme picker would leave it behind.
	public static final int DEFAULT_WINDOW = 0xFFFF74A8;
	public static final int DEFAULT_RING = 0xFFFF74A8;

	private static final ColorSetting window = new ColorSetting("gui_accent", DEFAULT_WINDOW);
	private static final ColorSetting ring = new ColorSetting("radial_accent", DEFAULT_RING);

	/** Last values pushed out, so a frame only reacts to a real change. */
	private static int appliedWindow = DEFAULT_WINDOW;
	private static int appliedRing = DEFAULT_RING;

	private GuiColors() {
	}

	public static ColorSetting window() {
		return window;
	}

	public static ColorSetting ring() {
		return ring;
	}

	public static int windowArgb() {
		return opaque(window.argb());
	}

	public static int ringArgb() {
		return opaque(ring.argb());
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

	/** Applies the persisted values once, at startup. */
	public static void load(int windowArgb, int ringArgb) {
		window.set(opaque(windowArgb));
		ring.set(opaque(ringArgb));
		appliedWindow = window.argb();
		appliedRing = ring.argb();
		// Write the sanitised values back, so an old translucent accent is not
		// saved again on the next close and has to be corrected every launch.
		ClientConfig.get().setGuiAccent(appliedWindow);
		ClientConfig.get().setRadialAccent(appliedRing);
		UiTheme.setAccent(appliedWindow);
	}

	/**
	 * Pushes the current values into the live theme (window) and the config
	 * (both). Returns true when something changed this frame.
	 */
	public static boolean sync() {
		boolean changed = false;
		int nowWindow = windowArgb();
		int nowRing = ringArgb();
		ClientConfig config = ClientConfig.get();
		if (nowWindow != appliedWindow) {
			appliedWindow = nowWindow;
			UiTheme.setAccent(nowWindow);
			config.setGuiAccent(nowWindow);
			changed = true;
		}
		if (nowRing != appliedRing) {
			appliedRing = nowRing;
			config.setRadialAccent(nowRing);
			changed = true;
		}
		return changed;
	}
}
