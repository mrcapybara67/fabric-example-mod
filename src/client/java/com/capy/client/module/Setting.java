package com.capy.client.module;

import java.util.function.BooleanSupplier;

/**
 * A single configurable value owned by a module.
 * The translation key is resolved as: capy.module.&lt;moduleId&gt;.&lt;key&gt;
 *
 * <p>A setting can depend on another setting: {@link #visibleWhen} carries the
 * rule, and the ClickGUI asks {@link #visible()} before it lays a row out. The
 * rule is evaluated from the live value on every frame, so a dependent row is
 * never drawn one frame after its parent changed - it disappears in the same
 * frame the switch was clicked.</p>
 *
 * <p>The metadata lives on the setting itself rather than on a wrapper view:
 * a wrapper would need an unchecked cast to keep a module field's concrete
 * type, and that cast fails at runtime for a value that is genuinely another
 * class.</p>
 */
public abstract class Setting<T> {
	private final String key;
	private final T defaultValue;
	private BooleanSupplier visibility;
	private String tip = "";

	protected Setting(String key, T defaultValue) {
		this.key = key;
		this.defaultValue = defaultValue;
		this.value = defaultValue;
	}

	protected T value;

	public String key() {
		return key;
	}

	/** The value this setting falls back to when it is reset. */
	protected T defaultValue() {
		return defaultValue;
	}

	/** Makes this row depend on a condition. */
	public Setting<T> visibleWhen(BooleanSupplier condition) {
		this.visibility = condition;
		return this;
	}

	/** A short, plain-language explanation shown in the settings popup. */
	public Setting<T> tip(String text) {
		this.tip = text == null ? "" : text;
		return this;
	}

	/** True unless a visibility rule says otherwise. */
	public boolean visible() {
		return visibility == null || visibility.getAsBoolean();
	}

	/** The short explanation, never null. */
	public String tip() {
		return tip;
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
