package com.capy.client.module;

/**
 * Boolean setting, rendered as a switch. Used for the on/off options of the
 * Anti Admin module.
 */
public class BooleanSetting extends Setting<Boolean> {
	public BooleanSetting(String key, boolean defaultValue) {
		super(key, defaultValue);
	}

	public boolean value() {
		Boolean current = get();
		return current != null && current;
	}

	public void toggle() {
		set(!value());
	}
}
