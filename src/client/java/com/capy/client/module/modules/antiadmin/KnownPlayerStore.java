package com.capy.client.module.modules.antiadmin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

/**
 * Per-server memory of every account the module has caught.
 *
 * Keeping the records separate per host is the point: a staff member on one
 * server says nothing about the same name on another, and merging them would
 * make a shared username look like a repeat offender everywhere.
 *
 * The file is written through a temporary file and moved into place, so a
 * crash while saving cannot leave a truncated store behind, and every failure
 * is logged rather than thrown — a read-only config directory costs the user
 * their history, not their game.
 */
public final class KnownPlayerStore {
	private static final Logger LOGGER = LogUtils.getLogger();
	/** Records kept per server. The oldest are dropped first. */
	private static final int MAX_PER_SERVER = 500;
	private static final int MAX_HOSTS = 64;
	private static final String FILE_NAME = "capy-known-players.json";

	private final Path file;
	private final Map<String, Map<UUID, KnownPlayer>> servers = new HashMap<>();
	private String host = "singleplayer";

	public KnownPlayerStore() {
		Path dir;
		try {
			dir = FabricLoader.getInstance().getConfigDir();
		} catch (RuntimeException e) {
			dir = Path.of("config");
		}
		this.file = dir.resolve(FILE_NAME);
	}

	/** Points the store at a server. Called on join and on a profile change. */
	public void bind(String serverHost) {
		this.host = sanitizeHost(serverHost);
	}

	public String host() {
		return host;
	}

	/** Loads every server's records. A missing or broken file is not an error. */
	public void load() {
		servers.clear();
		if (!Files.exists(file)) {
			return;
		}
		try {
			String text = Files.readString(file, StandardCharsets.UTF_8);
			if (text.isBlank()) {
				return;
			}
			JsonObject root = JsonParser.parseString(text).getAsJsonObject();
			JsonObject serversJson = root.has("servers") ? root.getAsJsonObject("servers") : new JsonObject();
			for (Map.Entry<String, JsonElement> entry : serversJson.entrySet()) {
				if (!entry.getValue().isJsonArray()) {
					continue;
				}
				Map<UUID, KnownPlayer> players = new HashMap<>();
				for (JsonElement element : entry.getValue().getAsJsonArray()) {
					if (!element.isJsonObject()) {
						continue;
					}
					KnownPlayer player = KnownPlayer.fromJson(element.getAsJsonObject());
					if (player != null) {
						players.put(player.uuid(), player);
					}
				}
				if (!players.isEmpty()) {
					servers.put(entry.getKey(), players);
				}
			}
			LOGGER.debug("Capy: loaded {} server(s) of known players.", servers.size());
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Capy: could not read {} ({}).", file, e.toString());
		}
	}

	/** Writes every server's records, trimmed to the newest per host. */
	public void save() {
		try {
			JsonObject root = new JsonObject();
			root.addProperty("version", 1);
			JsonObject serversJson = new JsonObject();
			for (Map.Entry<String, Map<UUID, KnownPlayer>> entry : servers.entrySet()) {
				JsonArray array = new JsonArray();
				for (KnownPlayer player : newest(entry.getValue())) {
					array.add(player.toJson());
				}
				if (!array.isEmpty()) {
					serversJson.add(entry.getKey(), array);
				}
			}
			root.add("servers", serversJson);
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling(FILE_NAME + ".tmp");
			Files.writeString(temp, root.toString(), StandardCharsets.UTF_8);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Capy: could not save {} ({}).", file, e.toString());
		}
	}

	/** The record for an account on the bound server, or null. */
	public KnownPlayer get(UUID uuid) {
		Map<UUID, KnownPlayer> players = servers.get(host);
		return players == null ? null : players.get(uuid);
	}

	/** Every record for the bound server. */
	public Collection<KnownPlayer> all() {
		Map<UUID, KnownPlayer> players = servers.get(host);
		return players == null ? List.of() : List.copyOf(players.values());
	}

	/**
	 * Records one detection, creating the player on first sight.
	 *
	 * @return the stored record after the update, or null when the uuid is
	 *         missing (a spatial subject has no account to remember)
	 */
	public KnownPlayer note(UUID uuid, String name, BlockPos pos, DetectionKind kind, long now) {
		if (uuid == null || SignalBuffer.isSpatial(uuid)) {
			return null;
		}
		Map<UUID, KnownPlayer> players = servers.computeIfAbsent(host, key -> {
			trimHosts();
			return new HashMap<>();
		});
		KnownPlayer existing = players.get(uuid);
		KnownPlayer updated = existing == null
				? KnownPlayer.firstSeen(uuid, name == null ? "unknown" : name, pos, kind, now)
				: existing.detected(kind, pos, name, now);
		players.put(uuid, updated);
		return updated;
	}

	/** Total records across every server, for the settings tooltip. */
	public int total() {
		int total = 0;
		for (Map<UUID, KnownPlayer> players : servers.values()) {
			total += players.size();
		}
		return total;
	}

	public void clear() {
		servers.clear();
	}

	private List<KnownPlayer> newest(Map<UUID, KnownPlayer> players) {
		List<KnownPlayer> list = new ArrayList<>(players.values());
		list.sort(Comparator.comparingLong(KnownPlayer::lastSeen).reversed());
		return list.size() > MAX_PER_SERVER ? list.subList(0, MAX_PER_SERVER) : list;
	}

	/** Keeps the number of remembered servers bounded. */
	private void trimHosts() {
		if (servers.size() < MAX_HOSTS) {
			return;
		}
		String oldest = null;
		long oldestSeen = Long.MAX_VALUE;
		for (Map.Entry<String, Map<UUID, KnownPlayer>> entry : servers.entrySet()) {
			long newest = 0L;
			for (KnownPlayer player : entry.getValue().values()) {
				newest = Math.max(newest, player.lastSeen());
			}
			if (newest < oldestSeen) {
				oldestSeen = newest;
				oldest = entry.getKey();
			}
		}
		if (oldest != null) {
			servers.remove(oldest);
		}
	}

	/** A stable, readable key for a host: lowercase, no port, no whitespace. */
	public static String sanitizeHost(String serverHost) {
		if (serverHost == null || serverHost.isBlank()) {
			return "singleplayer";
		}
		String clean = serverHost.trim().toLowerCase(Locale.ROOT);
		int colon = clean.lastIndexOf(':');
		if (colon > 0 && clean.indexOf(']') < colon) {
			clean = clean.substring(0, colon);
		}
		return clean.replaceAll("[^a-z0-9._\\-\\[\\]:]", "_");
	}
}
