package com.griefer.client.module;

/**
 * Module categories. Each carries its own accent color used by the ClickGUI.
 *
 * World / Visual / DonutSMP / Misc are intentionally empty for now — the GUI
 * renders them as clean empty-state sections until modules are added.
 */
public enum Category {
	BASE_FINDER("Base Finder", 0xFF6FA8DC),
	WORLD("World", 0xFF8FBC7F),
	VISUAL("Visual", 0xFFC9A86A),
	DONUT_SMP("DonutSMP", 0xFFD98CB3),
	MISC("Misc", 0xFF9DA5B4);

	public final String title;
	public final int accent;

	Category(String title, int accent) {
		this.title = title;
		this.accent = accent;
	}
}
