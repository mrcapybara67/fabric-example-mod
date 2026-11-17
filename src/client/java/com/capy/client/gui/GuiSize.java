package com.capy.client.gui;

import com.capy.client.config.ClientConfig;

/**
 * Interface size presets for the ClickGUI.
 *
 * <p>The preset scales the whole interface, type included, around the same
 * origin. The old version only widened the shell, so the text, cards and
 * spacing stayed exactly the same and the control looked broken. A screen
 * using the preset lays the frame out in a virtual viewport of
 * {@code physicalSize / scale}, then draws that viewport at {@code scale}.</p>
 */
public enum GuiSize {
	SMALL(0.86f, "Small"),
	NORMAL(1.0f, "Normal"),
	LARGE(1.16f, "Large");

	private final float scale;
	private final String label;

	GuiSize(float scale, String label) {
		this.scale = scale;
		this.label = label;
	}

	/** Uniform scale for every GUI coordinate, including text. */
	public float scale() {
		return scale;
	}

	public String label() {
		return label;
	}

	public static GuiSize current() {
		return ClientConfig.get().guiSize();
	}

	public static GuiSize from(String value, GuiSize fallback) {
		if (value == null) {
			return fallback;
		}
		for (GuiSize size : values()) {
			if (size.name().equalsIgnoreCase(value)) {
				return size;
			}
		}
		return fallback;
	}
}
