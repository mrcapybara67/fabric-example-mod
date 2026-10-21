package com.capy.client.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Defensive readers for the JSON configuration file.
 *
 * Every getter checks the type of the value it finds: a configuration file
 * that was hand-edited (or written by an older version of the client) must
 * never crash the game, it must simply fall back to the default.
 */
public final class ConfigValues {
	private ConfigValues() {
	}

	/** Reads an integer, falling back when the key is missing or not a number. */
	public static int intValue(JsonObject json, String key, int fallback) {
		if (json == null) {
			return fallback;
		}
		JsonElement element = json.get(key);
		if (element == null || !element.isJsonPrimitive()) {
			return fallback;
		}
		try {
			return element.getAsInt();
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	/** Reads a long, falling back when the key is missing or not a number. */
	public static long longValue(JsonObject json, String key, long fallback) {
		if (json == null) {
			return fallback;
		}
		JsonElement element = json.get(key);
		if (element == null || !element.isJsonPrimitive()) {
			return fallback;
		}
		try {
			return element.getAsLong();
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	/** Reads a boolean, falling back when the key is missing or not a boolean. */
	public static boolean boolValue(JsonObject json, String key, boolean fallback) {
		if (json == null) {
			return fallback;
		}
		JsonElement element = json.get(key);
		if (element == null || !element.isJsonPrimitive()) {
			return fallback;
		}
		try {
			return element.getAsBoolean();
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	/** Reads a string, falling back when the key is missing or not a string. */
	public static String stringValue(JsonObject json, String key, String fallback) {
		if (json == null) {
			return fallback;
		}
		JsonElement element = json.get(key);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			return fallback;
		}
		try {
			return element.getAsString();
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	/** Reads a list of strings; anything that is not a string is skipped. */
	public static List<String> stringList(JsonObject json, String key) {
		List<String> out = new ArrayList<>();
		if (json == null) {
			return out;
		}
		JsonElement element = json.get(key);
		if (element == null || !element.isJsonArray()) {
			return out;
		}
		JsonArray array = element.getAsJsonArray();
		for (JsonElement item : array) {
			if (item == null || !item.isJsonPrimitive()) {
				continue;
			}
			try {
				out.add(item.getAsString());
			} catch (RuntimeException ignored) {
				// Skip values that cannot be read as strings.
			}
		}
		return out;
	}
}
