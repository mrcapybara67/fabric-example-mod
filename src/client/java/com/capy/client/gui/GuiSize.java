package com.capy.client.gui;

import com.capy.client.config.ClientConfig;

/**
 * Window size presets for the ClickGUI.
 *
 * <p>The two-column grid already keeps the window short; this controls how
 * wide it opens. Only the shell is scaled, not the type, so text stays crisp
 * at every preset instead of being scaled up as a bitmap.</p>
 */
public enum GuiSize {
	SMALL(0.82f, "Small"),
	NORMAL(1.0f, "Normal"),
	LARGE(1.16f, "Large");

	private final float widthScale;
	private final String label;

	GuiSize(float widthScale, String label) {
		this.widthScale = widthScale;
		this.label = label;
	}

	public float widthScale() {
		return widthScale;
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
