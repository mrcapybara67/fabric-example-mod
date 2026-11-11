package com.capy.client.module.modules.movement;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded per-host movement profiles.
 *
 * <p>The map is intentionally capped and insertion ordered. A profile is tiny,
 * but a client that joins hundreds of servers in one session must not keep a
 * record of every host forever. The oldest entry is evicted, not the current
 * one.</p>
 */
public final class MovementProfiles {
	private static final int MAX_PROFILES = 64;
	private static final MovementProfiles INSTANCE = new MovementProfiles();
	private final Map<String, MovementProfile> profiles = new LinkedHashMap<>();

	private MovementProfiles() {
	}

	public static MovementProfiles get() {
		return INSTANCE;
	}

	public synchronized MovementProfile profile(String host) {
		String key = key(host);
		MovementProfile profile = profiles.get(key);
		if (profile == null) {
			profile = MovementProfile.defaults(key);
			profiles.put(key, profile);
			trim();
		}
		return profile;
	}

	public synchronized MovementProfile lower(String host) {
		MovementProfile profile = profile(host);
		profile.lower();
		return profile;
	}

	public synchronized MovementProfile raise(String host) {
		MovementProfile profile = profile(host);
		profile.raise();
		return profile;
	}

	public synchronized void clear() {
		profiles.clear();
	}

	public synchronized int size() {
		return profiles.size();
	}

	public synchronized JsonObject toJson() {
		JsonObject root = new JsonObject();
		for (Map.Entry<String, MovementProfile> entry : profiles.entrySet()) {
			root.add(entry.getKey(), entry.getValue().toJson());
		}
		return root;
	}

	public synchronized void load(JsonObject root) {
		profiles.clear();
		if (root == null) {
			return;
		}
		for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
			if (entry.getValue() != null && entry.getValue().isJsonObject()) {
				profiles.put(key(entry.getKey()),
						MovementProfile.fromJson(entry.getKey(), entry.getValue().getAsJsonObject()));
			}
			if (profiles.size() >= MAX_PROFILES) {
				break;
			}
		}
	}

	private void trim() {
		while (profiles.size() > MAX_PROFILES) {
			String eldest = profiles.keySet().iterator().next();
			profiles.remove(eldest);
		}
	}

	private static String key(String host) {
		return host == null || host.isBlank() ? "generic" : host;
	}
}
