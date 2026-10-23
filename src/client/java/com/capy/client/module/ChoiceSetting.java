package com.capy.client.module;

/**
 * A setting that picks one of a small, fixed list of named options.
 *
 * The value stored in the config is the option <em>id</em> (for example
 * {@code "butterfly"}), never its position: reordering or inserting an option
 * later must not silently turn every existing choice into a different one.
 * {@link #select(String)} does that lookup and reports whether the stored id is
 * still known, so a module can fall back to its default instead of guessing.
 *
 * The index is clamped on every write, so a hand edited config can never point
 * past the end of the list.
 */
public class ChoiceSetting extends Setting<Integer> {
	private final String[] options;

	/**
	 * @param key          setting key, resolved as
	 *                     {@code capy.module.<module>.<key>}
	 * @param defaultIndex index of the default option
	 * @param options      option ids, in display order; must not be empty
	 */
	public ChoiceSetting(String key, int defaultIndex, String... options) {
		super(key, defaultIndex);
		if (options == null || options.length == 0) {
			throw new IllegalArgumentException("ChoiceSetting '" + key + "' needs at least one option");
		}
		this.options = options.clone();
		this.value = clamp(defaultIndex);
	}

	/** Option ids, in display order. */
	public String[] options() {
		return options.clone();
	}

	/** The selected index, always inside the option list. */
	public int index() {
		return clamp(value == null ? 0 : value);
	}

	/** The selected option id. */
	public String option() {
		return options[index()];
	}

	/** True when the selected option has the given id. */
	public boolean is(String id) {
		return option().equals(id);
	}

	/**
	 * Selects the option with the given id.
	 *
	 * @return true when the id was known; false leaves the value untouched
	 */
	public boolean select(String id) {
		if (id == null) {
			return false;
		}
		for (int i = 0; i < options.length; i++) {
			if (options[i].equals(id)) {
				set(i);
				return true;
			}
		}
		return false;
	}

	/** Moves the selection by {@code delta} steps, wrapping at both ends. */
	public void cycle(int delta) {
		set(Math.floorMod(index() + delta, options.length));
	}

	@Override
	public void set(Integer value) {
		super.set(clamp(value == null ? 0 : value));
	}

	@Override
	public void reset() {
		set(defaultValue());
	}

	private int clamp(int index) {
		return Math.max(0, Math.min(options.length - 1, index));
	}
}
