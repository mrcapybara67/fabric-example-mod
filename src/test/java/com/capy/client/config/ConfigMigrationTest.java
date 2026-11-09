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
				{"theme":"sakura","gui_style":"radial","radial_accent":-1,
				 "gui_accent":-16740568,"modules":{}}
				""").getAsJsonObject();

		assertDoesNotThrow(() -> ConfigMigration.removeLegacyGuiKeys(root));
		assertFalse(root.has("theme"));
		assertFalse(root.has("gui_accent"));
		assertFalse(root.has("gui_style"));
		assertFalse(root.has("radial_accent"));
		assertEquals(0, root.get("modules").getAsJsonObject().size());
	}
}
