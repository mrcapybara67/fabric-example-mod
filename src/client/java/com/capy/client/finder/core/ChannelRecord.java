package com.capy.client.finder.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Remembers, per server address, which packet channels were seen delivering
 * events from deep underground. It is presentation evidence only: nothing in
 * the finder changes because of this file.
 */
public final class ChannelRecord {
	public static final String FILE_NAME = "capy-sound-bypass-channels.json";
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final Map<String, Set<String>> DATA = new LinkedHashMap<>();
	private static boolean loaded;
	private static boolean dirty;
	private static Path loadedDir;

	private ChannelRecord() {
	}

	/** Channels already seen deep on this server; empty for a new or missing file. */
	public static synchronized Set<String> deepChannels(Path configDir, String server) {
		if (configDir == null) {
			return Set.of();
		}
		ensureLoaded(configDir);
		Set<String> channels = DATA.get(safe(server));
		return channels == null ? Set.of() : Set.copyOf(channels);
	}

	public static synchronized void note(Path configDir, String server, ChannelStats.Channel channel) {
		if (configDir == null || channel == null) {
			return;
		}
		ensureLoaded(configDir);
		DATA.computeIfAbsent(safe(server), key -> new LinkedHashSet<>())
				.add(channel.name().toLowerCase(Locale.ROOT));
		dirty = true;
	}

	public static synchronized void flush(Path configDir) {
		if (configDir == null) {
			return;
		}
		ensureLoaded(configDir);
		if (!dirty) {
			return;
		}
		Path file = configDir.resolve(FILE_NAME);
		Path temp = configDir.resolve(FILE_NAME + ".tmp");
		try {
			Files.createDirectories(configDir);
			JsonObject root = new JsonObject();
			for (Map.Entry<String, Set<String>> entry : DATA.entrySet()) {
				JsonArray channels = new JsonArray();
				for (String channel : entry.getValue()) {
					channels.add(channel);
				}
				root.add(entry.getKey(), channels);
			}
			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				GSON.toJson(root, writer);
			}
			Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			dirty = false;
		} catch (Exception ignored) {
			try {
				Files.deleteIfExists(temp);
			} catch (Exception ignoredAgain) {
				// The temp file is harmless; the next save retries.
			}
		}
	}

	private static void ensureLoaded(Path configDir) {
		if (configDir == null) {
			return;
		}
		if (loaded && configDir.equals(loadedDir)) {
			return;
		}
		DATA.clear();
		dirty = false;
		loaded = true;
		loadedDir = configDir;
		Path file = configDir.resolve(FILE_NAME);
		if (!Files.exists(file)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonElement root = JsonParser.parseReader(reader);
			if (!root.isJsonObject()) {
				return;
			}
			for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
				if (!entry.getValue().isJsonArray()) {
					continue;
				}
				Set<String> channels = new LinkedHashSet<>();
				for (JsonElement channel : entry.getValue().getAsJsonArray()) {
					if (channel.isJsonPrimitive()) {
						channels.add(channel.getAsString().toLowerCase(Locale.ROOT));
					}
				}
				DATA.put(entry.getKey(), channels);
			}
		} catch (Exception ignored) {
			// A broken record only costs the remembered channel names.
		}
	}

	private static String safe(String value) {
		return value == null || value.isBlank() ? "unknown" : value;
	}

	static void clearForTests() {
		DATA.clear();
		loaded = false;
		dirty = false;
		loadedDir = null;
	}
}
