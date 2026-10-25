package com.capy.client.module.modules.antiadmin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;

/**
 * What the client remembers about one account between sessions.
 *
 * Immutable on purpose: the store is read from the render thread and written
 * from the tick, so an observation updates a record by replacing it instead of
 * mutating a shared one. {@code timesDetected} therefore counts detections
 * across every session this server has seen.
 */
public record KnownPlayer(UUID uuid, String lastName, BlockPos lastPos, long lastSeen,
		int timesDetected, List<DetectionKind> kinds) {

	public KnownPlayer {
		kinds = kinds == null ? List.of() : List.copyOf(kinds);
	}

	/** A first sighting. */
	public static KnownPlayer firstSeen(UUID uuid, String name, BlockPos pos, DetectionKind kind, long now) {
		return new KnownPlayer(uuid, name, pos, now, 1,
				kind == null ? List.of() : List.of(kind));
	}

	/** This record with one more detection of {@code kind} folded in. */
	public KnownPlayer detected(DetectionKind kind, BlockPos pos, String name, long now) {
		List<DetectionKind> merged = kinds;
		if (kind != null && !kinds.contains(kind)) {
			merged = new ArrayList<>(kinds);
			merged.add(kind);
		}
		return new KnownPlayer(uuid,
				name == null || name.isEmpty() ? lastName : name,
				pos == null ? lastPos : pos,
				now,
				timesDetected + 1,
				merged);
	}

	/** This record moved to a new position without adding a detection. */
	public KnownPlayer moved(BlockPos pos, long now) {
		return new KnownPlayer(uuid, lastName, pos, now, timesDetected, kinds);
	}

	/** One line describing the record, used by the debug log. */
	public String summary() {
		return lastName + " · " + timesDetected + "x · " + kinds.size() + " signal(s)";
	}

	public JsonObject toJson() {
		JsonObject out = new JsonObject();
		out.addProperty("uuid", uuid.toString());
		out.addProperty("name", lastName);
		if (lastPos != null) {
			JsonArray pos = new JsonArray();
			pos.add(lastPos.getX());
			pos.add(lastPos.getY());
			pos.add(lastPos.getZ());
			out.add("pos", pos);
		}
		out.addProperty("lastSeen", lastSeen);
		out.addProperty("timesDetected", timesDetected);
		JsonArray kindsJson = new JsonArray();
		for (DetectionKind kind : kinds) {
			kindsJson.add(kind.name());
		}
		out.add("kinds", kindsJson);
		return out;
	}

	/**
	 * Reads one record. A hand edited file can contain anything, so an entry
	 * that cannot be understood is rejected rather than guessed at.
	 */
	public static KnownPlayer fromJson(JsonObject json) {
		UUID uuid;
		try {
			uuid = UUID.fromString(json.get("uuid").getAsString());
		} catch (RuntimeException e) {
			return null;
		}
		String name = json.has("name") ? json.get("name").getAsString() : "unknown";
		BlockPos pos = null;
		if (json.has("pos") && json.get("pos").isJsonArray()) {
			JsonArray array = json.getAsJsonArray("pos");
			if (array.size() == 3) {
				pos = new BlockPos(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt());
			}
		}
		long lastSeen = json.has("lastSeen") ? json.get("lastSeen").getAsLong() : 0L;
		int times = json.has("timesDetected") ? json.get("timesDetected").getAsInt() : 1;
		List<DetectionKind> kinds = new ArrayList<>();
		if (json.has("kinds") && json.get("kinds").isJsonArray()) {
			for (JsonElement element : json.getAsJsonArray("kinds")) {
				DetectionKind kind = DetectionKind.parse(element.getAsString());
				if (kind != null) {
					kinds.add(kind);
				}
			}
		}
		return new KnownPlayer(uuid, name, pos, lastSeen, times, kinds);
	}
}
