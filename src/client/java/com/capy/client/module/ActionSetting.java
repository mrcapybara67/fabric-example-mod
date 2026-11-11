package com.capy.client.module;

/**
 * A setting row that runs an action instead of storing a value.
 *
 * <p>It is a button in the module card. The stored integer is only a click
 * counter, so the row still participates in the normal settings list and has a
 * stable identity without pretending to be configurable state.</p>
 */
public class ActionSetting extends Setting<Integer> {
	private final Runnable action;
	private final String label;

	public ActionSetting(String key, Runnable action) {
		this(key, "Reset", action);
	}

	public ActionSetting(String key, String label, Runnable action) {
		super(key, 0);
		this.action = action;
		this.label = label == null || label.isBlank() ? "Reset" : label;
	}

	/** The text on the button, not the setting row label. */
	public String buttonLabel() {
		return label;
	}

	/** Runs the action and advances the click counter. */
	public void run() {
		if (action != null) {
			action.run();
		}
		set(get() == null ? 1 : get() + 1);
	}
}
