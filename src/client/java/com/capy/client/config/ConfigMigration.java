package com.capy.client.config;

import com.google.gson.JsonObject;

/**
 * Removes keys from the retired Radial GUI before a config is read.
 *
 * <p>Gson would ignore unknown fields anyway, but deleting them here keeps the
 * migration explicit and gives the old-key tests one small, dependency-free
 * entry point. The current window accent is untouched.</p>
 */
public final class ConfigMigration {
	private ConfigMigration() {
	}

	/** Removes retired GUI keys, including the old theme and window accent. */
	public static void removeLegacyGuiKeys(JsonObject root) {
		if (root == null) {
			return;
		}
		root.remove("gui_style");
		root.remove("radial_accent");
		root.remove("theme");
		root.remove("gui_accent");
		// The old knife module had no replacement: its keys are retired, but
		// leaving them in an old file must not fail the load.
		root.remove("custom_knife");
	}
}
