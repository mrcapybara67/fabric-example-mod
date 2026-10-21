package com.capy.client.gui;

import net.minecraft.network.chat.Component;

/**
 * Resolves the client's translation keys.
 *
 * The lookup is defensive on purpose: the GUI must keep working when the
 * language file is missing, when a key was renamed, and when the client runs
 * outside a fully started game (where no language is loaded yet). In every
 * one of those cases the caller supplied fallback is used instead, so the
 * interface is never blank and never shows a raw key.
 */
public final class UiLabels {
	private static final java.util.Map<String, String> OVERRIDES = new java.util.HashMap<>();

	private UiLabels() {
	}

	/**
	 * Registers a translation that takes precedence over the language file.
	 * Used by the offline preview so the review renders the real wording.
	 */
	public static void override(String key, String text) {
		if (key != null && text != null) {
			OVERRIDES.put(key, text);
		}
	}

	/**
	 * Translates a key, falling back to the given text when the key cannot be
	 * resolved.
	 */
	public static String translate(String key, String fallback) {
		if (key == null) {
			return fallback;
		}
		String override = OVERRIDES.get(key);
		if (override != null) {
			return override;
		}
		try {
			String text = Component.translatable(key).getString();
			if (text == null || text.isEmpty() || text.equals(key)) {
				return fallback;
			}
			return text;
		} catch (Throwable t) {
			// No language loaded, or the component failed to resolve.
			return fallback;
		}
	}

	/**
	 * Turns a setting key into a readable label: {@code face_alert} becomes
	 * "Face Alert". Used when no translation exists for the key.
	 */
	public static String humanize(String settingKey) {
		if (settingKey == null || settingKey.isEmpty()) {
			return "";
		}
		String[] words = settingKey.replace('_', ' ').split(" ");
		StringBuilder sb = new StringBuilder();
		for (String word : words) {
			if (word.isEmpty()) {
				continue;
			}
			if (!sb.isEmpty()) {
				sb.append(' ');
			}
			sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return sb.toString();
	}
}
