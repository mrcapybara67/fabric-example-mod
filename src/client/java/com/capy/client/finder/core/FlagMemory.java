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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small on-disk memory for finder flags. The key is module + server +
 * dimension, so a flag from one world cannot appear in another.
 */
public final class FlagMemory {
	public static final int MAX_FLAGS = 512;
	public static final String FILE_NAME = "capy-finder-flags.json";
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final Map<String, List<Stored>> DATA = new LinkedHashMap<>();
	private static boolean loaded;
	private static boolean dirty;

	private FlagMemory() {
	}

	public record Stored(long chunkKey, String reason, int score) {
	}

	public static synchronized List<Stored> load(Path configDir, String server, String dimension, String module) {
		ensureLoaded(configDir);
		List<Stored> list = DATA.get(key(server, dimension, module));
		return list == null ? List.of() : List.copyOf(list);
	}

	public static synchronized void save(Path configDir, String server, String dimension, String module,
			List<Stored> values) {
		ensureLoaded(configDir);
		DATA.put(key(server, dimension, module), new ArrayList<>(cap(values)));
		capServer(server);
		dirty = true;
	}

	public static synchronized void flush(Path configDir) {
		ensureLoaded(configDir);
		if (!dirty) {
			return;
		}
		Path file = configDir.resolve(FILE_NAME);
		Path temp = configDir.resolve(FILE_NAME + ".tmp");
		try {
			Files.createDirectories(configDir);
			JsonObject root = new JsonObject();
			for (Map.Entry<String, List<Stored>> entry : DATA.entrySet()) {
				root.add(entry.getKey(), encodeList(entry.getValue()));
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

	/** Newest values are kept when the list is too large. */
	public static List<Stored> cap(List<Stored> values) {
		if (values == null || values.isEmpty()) {
			return List.of();
		}
		int from = Math.max(0, values.size() - MAX_FLAGS);
		return List.copyOf(values.subList(from, values.size()));
	}

	static JsonArray encodeList(List<Stored> values) {
		JsonArray array = new JsonArray();
		for (Stored value : values) {
			JsonObject row = new JsonObject();
			row.addProperty("key", value.chunkKey());
			row.addProperty("reason", value.reason());
			row.addProperty("score", value.score());
			array.add(row);
		}
		return array;
	}

	static List<Stored> decodeList(JsonElement element) {
		if (element == null || !element.isJsonArray()) {
			return List.of();
		}
		List<Stored> out = new ArrayList<>();
		for (JsonElement item : element.getAsJsonArray()) {
			if (item == null || !item.isJsonObject()) {
				continue;
			}
			JsonObject row = item.getAsJsonObject();
			try {
				out.add(new Stored(row.get("key").getAsLong(),
						row.has("reason") ? row.get("reason").getAsString() : "signal",
						row.has("score") ? row.get("score").getAsInt() : 0));
			} catch (RuntimeException ignored) {
				// A hand-edited row is skipped rather than losing the file.
			}
		}
		return cap(out);
	}

	private static String key(String server, String dimension, String module) {
		return safe(server) + "\n" + safe(dimension) + "\n" + safe(module);
	}

	private static String safe(String value) {
		return value == null ? "" : value;
	}

	private static void ensureLoaded(Path configDir) {
		if (loaded) {
			return;
		}
		loaded = true;
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
				DATA.put(entry.getKey(), new ArrayList<>(decodeList(entry.getValue())));
			}
		} catch (Exception ignored) {
			// A broken flag file only costs the remembered flags.
		}
	}

	private static void capServer(String server) {
		int total = 0;
		List<List<Stored>> lists = new ArrayList<>();
		for (Map.Entry<String, List<Stored>> entry : DATA.entrySet()) {
			if (entry.getKey().startsWith(safe(server) + "\n")) {
				total += entry.getValue().size();
				lists.add(entry.getValue());
			}
		}
		while (total > MAX_FLAGS) {
			boolean removed = false;
			for (List<Stored> list : lists) {
				if (!list.isEmpty() && total > MAX_FLAGS) {
					list.remove(0);
					total--;
					removed = true;
				}
			}
			if (!removed) {
				return;
			}
		}
	}

	static void clearForTests() {
		DATA.clear();
		loaded = false;
		dirty = false;
	}
}
