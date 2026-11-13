package com.capy.client.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class ConfigMigrationTest {
	@Test
	void oldGuiStyleAndRadialAccentAreIgnored() {
		JsonObject root = JsonParser.parseString("""
				{"theme":"sakura","gui_style":"radial","radial_accent":-1,"custom_knife":{"style":"karambit"},
				 "gui_accent":-16740568,"modules":{"sus_chunk_finder":{"finder_debug":true},
				 "chunk_finder":{"light_debug":true},"chunk_finder_better":{"shaft_debug":true}}}
				""").getAsJsonObject();

		assertDoesNotThrow(() -> ConfigMigration.removeLegacyGuiKeys(root));
		assertFalse(root.has("theme"));
		assertFalse(root.has("gui_accent"));
		assertFalse(root.has("gui_style"));
		assertFalse(root.has("radial_accent"));
		assertFalse(root.has("custom_knife"));
		JsonObject modules = root.get("modules").getAsJsonObject();
		ConfigMigration.removeLegacyFinderKeys(modules);
		assertEquals(0, modules.size());
	}
}
