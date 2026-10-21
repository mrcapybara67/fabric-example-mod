package com.capy.client.module;

/**
 * Module categories. Each carries its own accent tint used by the ClickGUI.
 *
 * All accents stay inside the Sakura family so the interface reads as one
 * palette: pink is the client's accent, the categories only shift its
 * temperature slightly.
 *
 * World / Visual / DonutSMP / Misc are intentionally sparse — the GUI renders
 * an empty category as a clean, designed empty state.
 */
public enum Category {
	BASE_FINDER("Base Finder"),
	WORLD("World"),
	VISUAL("Visual"),
	DONUT_SMP("DonutSMP"),
	MISC("Misc"),
	HUD("HUD");

	public final String title;

	Category(String title) {
		this.title = title;
	}
}
