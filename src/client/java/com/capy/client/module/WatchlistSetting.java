package com.capy.client.module;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A watchlist of player names, owned by a module.
 *
 * The list is the single source of truth for "who do we care about". It is
 * validated on the way in (Java Edition usernames are 3-16 characters of
 * {@code A-Z}, {@code a-z}, {@code 0-9} and {@code _}), duplicates are
 * rejected case-insensitively, and matching is always case-insensitive
 * because servers are free to change the capitalisation of a name.
 */
public class WatchlistSetting extends Setting<List<String>> {
	/** Maximum number of watched names kept in the list. */
	public static final int MAX_ENTRIES = 64;

	public WatchlistSetting(String key) {
		super(key, new ArrayList<>());
	}

	/** The watched names, in insertion order. */
	public List<String> entries() {
		List<String> value = get();
		return value == null ? List.of() : value;
	}

	/**
	 * Validates a candidate name.
	 *
	 * @return true when the name is a syntactically valid Java Edition username
	 */
	public static boolean isValidName(String name) {
		if (name == null) {
			return false;
		}
		int length = name.length();
		if (length < 3 || length > 16) {
			return false;
		}
		for (int i = 0; i < length; i++) {
			char c = name.charAt(i);
			boolean valid = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
			if (!valid) {
				return false;
			}
		}
		return true;
	}

	/** True when the name is already watched (case-insensitive). */
	public boolean contains(String name) {
		if (name == null) {
			return false;
		}
		String needle = name.toLowerCase(Locale.ROOT);
		for (String entry : entries()) {
			if (entry.toLowerCase(Locale.ROOT).equals(needle)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Adds a name to the watchlist.
	 *
	 * @return {@link ListResult#ADDED} on success, otherwise the reason the
	 *         name was rejected
	 */
	public ListResult add(String name) {
		if (!isValidName(name)) {
			return ListResult.INVALID;
		}
		if (contains(name)) {
			return ListResult.DUPLICATE;
		}
		List<String> current = entries();
		if (current.size() >= MAX_ENTRIES) {
			return ListResult.FULL;
		}
		current.add(name);
		set(current);
		return ListResult.ADDED;
	}

	/** Removes a name (case-insensitive). Returns true when something was removed. */
	public boolean remove(String name) {
		if (name == null) {
			return false;
		}
		String needle = name.toLowerCase(Locale.ROOT);
		List<String> current = entries();
		for (int i = 0; i < current.size(); i++) {
			if (current.get(i).toLowerCase(Locale.ROOT).equals(needle)) {
				current.remove(i);
				set(current);
				return true;
			}
		}
		return false;
	}

	/** True when the given name is watched (case-insensitive). */
	public boolean matches(String name) {
		return contains(name);
	}

	@Override
	public void set(List<String> value) {
		if (value == null) {
			super.set(new ArrayList<>());
			return;
		}
		List<String> cleaned = new ArrayList<>();
		for (String entry : value) {
			if (entry == null || !isValidName(entry) || containsIn(cleaned, entry)) {
				continue;
			}
			cleaned.add(entry);
			if (cleaned.size() >= MAX_ENTRIES) {
				break;
			}
		}
		super.set(cleaned);
	}

	private static boolean containsIn(List<String> list, String name) {
		String needle = name.toLowerCase(Locale.ROOT);
		for (String entry : list) {
			if (entry.toLowerCase(Locale.ROOT).equals(needle)) {
				return true;
			}
		}
		return false;
	}
}
