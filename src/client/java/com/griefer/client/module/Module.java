package com.griefer.client.module;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for every client module. Holds name, category, toggle state and settings.
 */
public abstract class Module {
	private final Category category;
	private final String id;
	private final List<Setting<?>> settings = new ArrayList<>();
	private boolean enabled;

	protected Module(Category category, String id) {
		this.category = category;
		this.id = id;
	}

	public Category category() {
		return category;
	}

	public String id() {
		return id;
	}

	public String translationKey() {
		return "griefer.module." + id + ".name";
	}

	public String descriptionKey() {
		return "griefer.module." + id + ".desc";
	}

	public List<Setting<?>> settings() {
		return settings;
	}

	protected <S extends Setting<?>> S setting(S setting) {
		settings.add(setting);
		return setting;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void toggle() {
		setEnabled(!enabled);
	}

	public void setEnabled(boolean state) {
		if (enabled != state) {
			enabled = state;
			onToggle(state);
		}
	}

	/** Called when the toggle state actually changes. */
	protected void onToggle(boolean state) {
	}
}
