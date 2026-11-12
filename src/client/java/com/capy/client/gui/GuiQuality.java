package com.capy.client.gui;

import com.capy.client.config.ClientConfig;

/**
 * Three rendering budgets. HIGH keeps every decorative layer, BALANCED drops
 * the moving sheen and noise, and LOW keeps flat fills only so a weak mobile
 * GPU never pays for shadows or large translucent surfaces.
 */
public enum GuiQuality {
	LOW,
	BALANCED,
	HIGH;

	public static GuiQuality from(String value, GuiQuality fallback) {
		if (value == null) {
			return fallback;
		}
		for (GuiQuality quality : values()) {
			if (quality.name().equalsIgnoreCase(value)) {
				return quality;
			}
		}
		return fallback;
	}

	public static GuiQuality current() {
		return ClientConfig.get().guiQuality();
	}

	public boolean shadows() {
		return this != LOW;
	}

	public int shadowLayers() {
		return this == HIGH ? 3 : this == BALANCED ? 1 : 0;
	}

	public boolean sheen() {
		return this == HIGH;
	}

	public boolean noise() {
		return this != LOW;
	}

	public boolean glow() {
		return this != LOW;
	}
}
