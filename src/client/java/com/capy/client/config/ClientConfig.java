package com.capy.client.config;

import com.capy.client.module.Configurable;
import com.capy.client.module.Module;
import com.capy.client.module.ModuleManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client's own configuration file: {@code config/capy-client.json}.
 *
 * It holds everything the client must remember between sessions — the GUI
 * theme and the persisted state of every module that implements
 * {@link Configurable}.
 *
 * The keybind is deliberately <em>not</em> stored here: Minecraft persists
 * key mappings itself in {@code options.txt}, so the binding is saved and
 * restored by the vanilla mechanism and stays consistent with the Controls
 * screen.
 *
 * Robustness rules for this file:
 *   - a missing file is not an error, the defaults are used;
 *   - a malformed file is logged and ignored, the defaults are used;
 *   - individual malformed values fall back to their default;
 *   - the file is written through a temporary file and moved into place, so a
 *     crash mid-write can never leave a half written file behind.
 */
public final class ClientConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger(ClientConfig.class);
	private static final String FILE_NAME = "capy-client.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private static final String KEY_THEME = "theme";
	private static final String KEY_MODULES = "modules";

	private static ClientConfig instance = new ClientConfig();

	private String theme = "sakura";
	private final Map<String, JsonObject> moduleData = new LinkedHashMap<>();

	private ClientConfig() {
	}

	public static ClientConfig get() {
		return instance;
	}

	public String theme() {
		return theme;
	}

	public void setTheme(String theme) {
		if (theme != null && !theme.isEmpty()) {
			this.theme = theme;
		}
	}

	/** The stored JSON of a module (never null). */
	public JsonObject moduleData(String moduleId) {
		return moduleData.computeIfAbsent(moduleId, id -> new JsonObject());
	}

	/**
	 * Reads the configuration from {@code configDir} and applies it to the
	 * registered modules. Never throws: a broken file only costs the user
	 * their settings, not the game.
	 */
	public static ClientConfig load(Path configDir) {
		ClientConfig config = new ClientConfig();
		instance = config;
		Path file = configDir.resolve(FILE_NAME);

		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				JsonElement parsed = JsonParser.parseReader(reader);
				if (parsed != null && parsed.isJsonObject()) {
					config.readFrom(parsed.getAsJsonObject());
				} else {
					LOGGER.warn("Capy: {} is not a JSON object; using defaults.", file);
				}
			} catch (IOException | RuntimeException e) {
				LOGGER.warn("Capy: could not read {} ({}); using defaults.", file, e.toString());
			}
		}

		config.applyToModules();
		return config;
	}

	private void readFrom(JsonObject root) {
		String storedTheme = ConfigValues.stringValue(root, KEY_THEME, theme);
		theme = storedTheme;

		JsonElement modulesElement = root.get(KEY_MODULES);
		if (modulesElement != null && modulesElement.isJsonObject()) {
			JsonObject modules = modulesElement.getAsJsonObject();
			for (Map.Entry<String, JsonElement> entry : modules.entrySet()) {
				if (entry.getValue() != null && entry.getValue().isJsonObject()) {
					moduleData.put(entry.getKey(), entry.getValue().getAsJsonObject());
				}
			}
		}
	}

	/** Pushes the stored values into the modules that asked for them. */
	private void applyToModules() {
		for (Module module : ModuleManager.get().modules()) {
			if (module instanceof Configurable configurable) {
				try {
					configurable.loadConfig(moduleData(module.id()));
				} catch (RuntimeException e) {
					LOGGER.warn("Capy: module '{}' rejected its stored settings ({}); using defaults.",
							module.id(), e.toString());
				}
			}
		}
	}

	/** Collects the current module state into the JSON tree. */
	private JsonObject toJson() {
		JsonObject root = new JsonObject();
		root.addProperty(KEY_THEME, theme);

		JsonObject modules = new JsonObject();
		for (Module module : ModuleManager.get().modules()) {
			if (module instanceof Configurable configurable) {
				JsonObject data = new JsonObject();
				try {
					configurable.saveConfig(data);
				} catch (RuntimeException e) {
					LOGGER.warn("Capy: module '{}' failed to save its settings ({}).", module.id(), e.toString());
					continue;
				}
				modules.add(module.id(), data);
			}
		}
		root.add(KEY_MODULES, modules);
		return root;
	}

	/**
	 * Writes the configuration to {@code configDir}. Failures are logged, not
	 * thrown — a read-only config directory must not break the game.
	 */
	public void save(Path configDir) {
		Path file = configDir.resolve(FILE_NAME);
		Path temp = configDir.resolve(FILE_NAME + ".tmp");
		try {
			Files.createDirectories(configDir);
			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				GSON.toJson(toJson(), writer);
			}
			try {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				// Some file systems refuse REPLACE_EXISTING across providers.
				Files.copy(temp, file, StandardCopyOption.REPLACE_EXISTING);
				Files.deleteIfExists(temp);
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Capy: could not save {} ({}).", file, e.toString());
			try {
				Files.deleteIfExists(temp);
			} catch (IOException ignored) {
				// Nothing else to do; the temp file is harmless.
			}
		}
	}
}
