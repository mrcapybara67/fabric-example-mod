package com.capy.client.module;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/**
 * A list of block ids, owned by a module — the filter a block ESP scans for.
 *
 * Entries are normalised on the way in: {@code diamond_ore} becomes
 * {@code minecraft:diamond_ore}, surrounding whitespace is dropped and the
 * namespace is lower-cased, so the same block cannot be stored twice under two
 * spellings. An entry that is not a registered block is rejected instead of
 * being kept as a filter that can never match.
 */
public class BlockListSetting extends Setting<List<String>> {
	/** Maximum number of block ids kept in the list. */
	public static final int MAX_ENTRIES = 64;

	public BlockListSetting(String key) {
		super(key, new ArrayList<>());
	}

	/** The filtered block ids, in insertion order. */
	public List<String> entries() {
		List<String> value = get();
		return value == null ? List.of() : value;
	}

	/**
	 * Normalises a typed block id.
	 *
	 * @return the canonical {@code namespace:path} id, or null when the text is
	 *         not a block id at all
	 */
	public static String normalize(String raw) {
		if (raw == null) {
			return null;
		}
		String text = raw.trim().toLowerCase(Locale.ROOT);
		if (text.isEmpty()) {
			return null;
		}
		if (text.indexOf(':') < 0) {
			text = "minecraft:" + text;
		}
		Identifier id = Identifier.tryParse(text);
		if (id == null) {
			return null;
		}
		// Resolving is what makes "diamond_ore" and "diamnd_ore" different: the
		// second one is a typo and must be reported, not silently scanned for.
		return BuiltInRegistries.BLOCK.getOptional(id).isPresent() ? id.toString() : null;
	}

	/** True when the id is already in the list (case-insensitive). */
	public boolean contains(String id) {
		if (id == null) {
			return false;
		}
		for (String entry : entries()) {
			if (entry.equalsIgnoreCase(id)) {
				return true;
			}
		}
		return false;
	}

	/** Adds a block id to the filter. */
	public ListResult add(String raw) {
		String id = normalize(raw);
		if (id == null) {
			return ListResult.INVALID;
		}
		if (contains(id)) {
			return ListResult.DUPLICATE;
		}
		// A copy, so the edit never depends on the stored list being mutable.
		List<String> current = new ArrayList<>(entries());
		if (current.size() >= MAX_ENTRIES) {
			return ListResult.FULL;
		}
		current.add(id);
		set(current);
		return ListResult.ADDED;
	}

	/** Removes a block id (case-insensitive). Returns true when something was removed. */
	public boolean remove(String id) {
		if (id == null) {
			return false;
		}
		List<String> current = new ArrayList<>(entries());
		for (int i = 0; i < current.size(); i++) {
			if (current.get(i).equalsIgnoreCase(id)) {
				current.remove(i);
				set(current);
				return true;
			}
		}
		return false;
	}

	/**
	 * A cheap signature of the current entries, used to notice a change without
	 * rebuilding the resolution cache on every tick.
	 */
	public int signature() {
		List<String> current = entries();
		int hash = current.size() * 31;
		for (String entry : current) {
			hash = hash * 31 + entry.hashCode();
		}
		return hash;
	}

	@Override
	public void set(List<String> value) {
		if (value == null) {
			super.set(new ArrayList<>());
			return;
		}
		List<String> cleaned = new ArrayList<>();
		for (String entry : value) {
			String id = normalize(entry);
			if (id == null || containsIn(cleaned, id)) {
				continue;
			}
			cleaned.add(id);
			if (cleaned.size() >= MAX_ENTRIES) {
				break;
			}
		}
		super.set(cleaned);
	}

	private static boolean containsIn(List<String> list, String id) {
		for (String entry : list) {
			if (entry.equalsIgnoreCase(id)) {
				return true;
			}
		}
		return false;
	}
}
