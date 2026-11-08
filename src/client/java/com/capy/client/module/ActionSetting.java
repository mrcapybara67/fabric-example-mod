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

	public ActionSetting(String key, Runnable action) {
		super(key, 0);
		this.action = action;
	}

	/** Runs the action and advances the click counter. */
	public void run() {
		if (action != null) {
			action.run();
		}
		set(get() == null ? 1 : get() + 1);
	}
}
