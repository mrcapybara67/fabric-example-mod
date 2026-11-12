package com.capy.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.capy.client.gui.GuiQuality;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuiConfigMigrationTest {
	@TempDir
	Path tempDir;

	@Test
	void readsNewFieldsAndDiscardsRetiredGuiKeys() throws Exception {
		Path file = tempDir.resolve("capy-client.json");
		Files.writeString(file, """
				{
				  "gui_quality": "HIGH",
				  "gui_debug": true,
				  "gui_style": "radial",
				  "theme": "old",
				  "gui_accent": 123,
				  "modules": {}
				}
				""");
		ClientConfig config = ClientConfig.load(tempDir);
		assertEquals(GuiQuality.HIGH, config.guiQuality());
		assertTrue(config.guiDebug());

		JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
		ConfigMigration.removeLegacyGuiKeys(root);
		assertFalse(root.has("gui_style"));
		assertFalse(root.has("theme"));
		assertFalse(root.has("gui_accent"));
	}

	@Test
	void missingFileUsesBalancedQualityAndNoDebugOverlay() throws Exception {
		ClientConfig config = ClientConfig.load(tempDir);
		assertEquals(GuiQuality.BALANCED, config.guiQuality());
		assertFalse(config.guiDebug());
	}
}
