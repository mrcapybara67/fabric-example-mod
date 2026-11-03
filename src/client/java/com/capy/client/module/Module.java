package com.capy.client.module;

import com.capy.client.util.Guard;
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
		return "capy.module." + id + ".name";
	}

	public String descriptionKey() {
		return "capy.module." + id + ".desc";
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
			// Toggling is the one hook with no safety net of its own: the switch
			// is clicked from a screen, so an exception raised here runs inside
			// vanilla's mouse handling and takes the game down instead of just
			// failing the feature. Every other entry point this client installs
			// (tick, HUD, world pass, packets) is guarded; this one is now too,
			// which also covers the restore at startup, where a throwing
			// onToggle used to mean the client could not boot.
			Guard.run("toggle " + id, () -> onToggle(state));
		}
	}

	/** Called when the toggle state actually changes. */
	protected void onToggle(boolean state) {
	}
}
