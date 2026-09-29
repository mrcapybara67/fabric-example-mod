package com.griefer.client.module;

/**
 * A single configurable value owned by a module.
 * The translation key is resolved as: griefer.module.&lt;moduleId&gt;.&lt;key&gt;
 */
public abstract class Setting<T> {
	private final String key;
	private final T defaultValue;

	protected Setting(String key, T defaultValue) {
		this.key = key;
		this.defaultValue = defaultValue;
		this.value = defaultValue;
	}

	protected T value;

	public String key() {
		return key;
	}

	public T get() {
		return value;
	}

	public void set(T value) {
		this.value = value;
	}

	public void reset() {
		this.value = defaultValue;
	}
}
