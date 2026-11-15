package com.capy.client.stealth;

import com.capy.client.util.Guard;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Capy's own text table. It is deliberately outside assets/capy/lang so a
 * server-supplied translatable component can never resolve a Capy key through
 * the game's Language instance.
 */
public final class CapyText {
	private static final Identifier FILE = Identifier.fromNamespaceAndPath("capy", "text/en_us.json");
	private static final Map<String, String> TEXT = new HashMap<>();
	private static boolean loaded;
	private static boolean failed;

	private CapyText() {
	}

	public static String get(String key) {
		return get(key, key);
	}

	public static String get(String key, String fallback) {
		if (key == null) {
			return fallback;
		}
		ensureLoaded();
		String text = TEXT.get(key);
		return text == null || text.isBlank() ? fallback : text;
	}

	/** Loads once, from the resource manager, after the client resources exist. */
	public static synchronized void ensureLoaded() {
		if (loaded || failed) {
			return;
		}
		ResourceManager manager;
		try {
			Minecraft mc = Minecraft.getInstance();
			manager = mc == null ? null : mc.getResourceManager();
		} catch (RuntimeException e) {
			manager = null;
		}
		if (manager == null) {
			return;
		}
		Optional<Resource> resource = manager.getResource(FILE);
		if (resource.isEmpty()) {
			failed = true;
			Guard.report("capy text resource", new IllegalStateException("missing " + FILE));
			return;
		}
		try (InputStream in = resource.get().open();
				BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(in,
						java.nio.charset.StandardCharsets.UTF_8))) {
			JsonElement root = JsonParser.parseReader(reader);
			if (!root.isJsonObject()) {
				failed = true;
				return;
			}
			JsonObject object = root.getAsJsonObject();
			for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
				if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString()) {
					TEXT.put(entry.getKey(), entry.getValue().getAsString());
				}
			}
			loaded = true;
		} catch (Exception e) {
			failed = true;
			Guard.report("capy text load", e);
		}
	}

	/** Reload support for the client resource reload; the file is tiny. */
	public static synchronized void reload() {
		TEXT.clear();
		loaded = false;
		failed = false;
		ensureLoaded();
	}
}
