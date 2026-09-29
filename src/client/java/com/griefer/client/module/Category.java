package com.griefer.client.module;

/**
 * Module categories. Each carries its own accent color used by the ClickGUI.
 */
public enum Category {
	BASE_FINDER("Base Finder", 0xFF59C1FF);

	public final String title;
	public final int accent;

	Category(String title, int accent) {
		this.title = title;
		this.accent = accent;
	}
}
