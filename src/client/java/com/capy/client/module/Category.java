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
	BASE_FINDER("Base Finder", 0xFFFF8FB8),
	WORLD("World", 0xFFC9A6D8),
	VISUAL("Visual", 0xFFFFC4D8),
	DONUT_SMP("DonutSMP", 0xFFD9578B),
	MISC("Misc", 0xFFBBAAB7);

	public final String title;
	public final int accent;

	Category(String title, int accent) {
		this.title = title;
		this.accent = accent;
	}
}
