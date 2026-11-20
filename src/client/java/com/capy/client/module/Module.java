package com.capy.client.module;

import com.capy.client.util.Guard;
import com.google.gson.JsonObject;
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

	/**
	 * Registers a setting that is only shown while {@code visibleWhen} is true.
	 * Declared as a helper because Java cannot infer the concrete type through
	 * a self-typed generic method in a field initialiser.
	 */
	protected <S extends Setting<?>> S setting(S setting, java.util.function.BooleanSupplier visibleWhen) {
		setting.visibleWhen(visibleWhen);
		settings.add(setting);
		return setting;
	}

	/** Attaches a tooltip while keeping the field's concrete setting type. */
	protected <S extends Setting<?>> S describe(S setting, String tip) {
		setting.tip(tip);
		return setting;
	}

	/**
	 * Loads every registered setting from the module's JSON.
	 *
	 * <p>Called by the config before the module's own loader, so a module can
	 * still translate its legacy keys afterwards. Public because the config
	 * owner lives in another package.</p>
	 */
	public void loadSettings(JsonObject json) {
		for (Setting<?> setting : settings) {
			SettingCodec.load(setting, json);
		}
	}

	/** Saves every registered setting into the module's JSON. */
	public void saveSettings(JsonObject json) {
		for (Setting<?> setting : settings) {
			SettingCodec.save(setting, json);
		}
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
			// The switch is clicked on the render thread. Only the flag changes
			// here; the feature hook is coalesced and run from the next client
			// tick, so a finder registration or an atmosphere rebuild cannot
			// stall the click and make the card grid skip a frame.
			ModuleManager.get().queueToggle(this, state);
		}
	}

	/** Runs the deferred hook. Called by {@link ModuleManager} on the client tick. */
	void applyToggleState(boolean state) {
		// A throwing onToggle must not reach vanilla's tick loop, where it would
		// be a crash rather than a skipped feature.
		Guard.run("toggle " + id, () -> onToggle(state));
	}

	/** Called from the next client tick after the toggle state changed. */
	protected void onToggle(boolean state) {
	}
}
