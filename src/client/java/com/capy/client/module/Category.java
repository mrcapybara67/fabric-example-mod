package com.capy.client.module;

/**
 * Module categories. Each carries its own accent tint used by the ClickGUI.
 *
 * All accents stay inside the Sakura family so the interface reads as one
 * palette: pink is the client's accent, the categories only shift its
 * temperature slightly.
 *
 * The radial GUI draws one ring node per category, and each node uses the
 * category's own accent so the categories are recognisable before their label
 * is read.
 */
public enum Category {
	BASE_FINDER("Base Finder", 0xFF8FB8FF),
	WORLD("World", 0xFF7CF0C0),
	VISUAL("Visual", 0xFFC8A2FF),
	DONUT_SMP("DonutSMP", 0xFFFF8FB8),
	MISC("Misc", 0xFFFFD166),
	HUD("HUD", 0xFF6FDFFF);

	public final String title;
	/** The category's own tint, used by the radial ring and its badges. */
	public final int accent;

	Category(String title, int accent) {
		this.title = title;
		this.accent = accent;
	}
}
