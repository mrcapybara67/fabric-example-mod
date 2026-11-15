package com.capy.client.stealth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class StealthLanguageTest {
	@Test
	void capyKeysAreNotInTheGameLanguageFile() throws Exception {
		Path lang = Path.of("src/main/resources/assets/capy/lang/en_us.json");
		try (Reader reader = Files.newBufferedReader(lang)) {
			JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
			for (String key : root.keySet()) {
				assertFalse(key.startsWith("capy.") || key.startsWith("key.capy.")
						|| key.startsWith("key.category.capy."), key);
			}
		}
	}

	@Test
	void capyTextUsesItsOwnResourcePath() {
		assertTrue(Files.exists(Path.of("src/client/resources/assets/capy/text/en_us.json")));
		assertFalse(Files.exists(Path.of("src/client/resources/assets/capy/text/lang")));
	}

	@Test
	void aCapyKeyCannotResolveThroughTheServerComponentResolver() {
		assertEquals("key.capy.open_gui",
				Component.translatable("key.capy.open_gui").getString());
	}
}
