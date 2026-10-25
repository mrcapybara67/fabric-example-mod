package com.capy.client.module;

/**
 * A free text setting.
 *
 * Kept deliberately small: the value is trimmed on the way in and never allowed
 * to be null, so a config file that stores a number where a string belongs
 * cannot hand a module a type it does not expect. Validation that depends on
 * what the text means (a URL, a name) belongs to the module that reads it.
 */
public class StringSetting extends Setting<String> {
	public StringSetting(String key, String defaultValue) {
		super(key, defaultValue == null ? "" : defaultValue);
	}

	/** The stored text, never null. */
	public String value() {
		String current = get();
		return current == null ? "" : current;
	}

	/** True when something has been entered. */
	public boolean present() {
		return !value().isBlank();
	}

	@Override
	public void set(String value) {
		super.set(value == null ? "" : value.trim());
	}
}
