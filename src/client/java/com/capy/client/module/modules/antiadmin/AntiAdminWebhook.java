package com.capy.client.module.modules.antiadmin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ships HIGH and CERTAIN detections to a Discord webhook.
 *
 * The webhook is the reason ghost mode exists: it lets the user leave the
 * client unattended and still learn what happened. Two rules follow from that:
 *
 * <ul>
 *   <li>nothing is sent below {@link Confidence#HIGH}, because a hook that
 *       pings on every stray particle is a hook nobody reads;</li>
 *   <li>sends are serialised and rate limited to one per {@link #MIN_GAP_MS},
 *       because Discord answers a burst with a 429 that drops the important
 *       alert along with the noise.</li>
 * </ul>
 *
 * Everything runs on the JDK's own HTTP client and a single shared instance, so
 * a session with a few hundred detections creates one connection pool, not one
 * per alert.
 */
public final class AntiAdminWebhook {
	private static final Logger LOGGER = LoggerFactory.getLogger(AntiAdminWebhook.class);
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	/** Discord rejects an embed field over 1024 characters. */
	private static final int MAX_FIELD = 1000;
	/** No more than one request per second and a half. */
	private static final long MIN_GAP_MS = 1_500L;

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();

	private final AtomicLong lastSent = new AtomicLong();
	private volatile String url;

	public AntiAdminWebhook(String url) {
		configure(url);
	}

	/** Points the hook at a new URL. Blank or malformed URLs disable it. */
	public void configure(String url) {
		this.url = sanitize(url);
	}

	/** True when a usable URL is configured. */
	public boolean configured() {
		return url != null;
	}

	/**
	 * Sends one detection, if it is loud enough and the URL is usable.
	 *
	 * Returns immediately: the request is queued on the HTTP client, so the
	 * tick that raised the alert never waits on the network.
	 */
	public void send(AlertRecord record) {
		if (record == null || url == null || !record.confidence().atLeast(Confidence.HIGH)) {
			return;
		}
		long now = System.currentTimeMillis();
		long previous = lastSent.get();
		if (now - previous < MIN_GAP_MS || !lastSent.compareAndSet(previous, now)) {
			return;
		}
		String body = GSON.toJson(payload(record));
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.header("Content-Type", "application/json")
				.header("User-Agent", "CapyClient")
				.timeout(Duration.ofSeconds(8))
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();
		HTTP.sendAsync(request, HttpResponse.BodyHandlers.discarding())
				.thenAccept(response -> {
					int status = response.statusCode();
					if (status >= 300) {
						// A 429 or a deleted hook is worth one line, not a stack.
						LOGGER.warn("Capy: webhook answered {}", status);
					}
				})
				.exceptionally(error -> {
					LOGGER.warn("Capy: webhook failed ({})", error.toString());
					return null;
				});
	}

	/** The JSON body: one embed, four inline fields and the evidence block. */
	private JsonObject payload(AlertRecord record) {
		JsonObject embed = new JsonObject();
		embed.addProperty("title", title(record));
		embed.addProperty("color", record.confidence().argb() & 0xFFFFFF);
		embed.addProperty("timestamp", Instant.ofEpochMilli(record.timestamp()).toString());
		embed.addProperty("description", record.tagLine().isEmpty()
				? "no signal detail"
				: trim(record.tagLine(), MAX_FIELD));

		JsonArray fields = new JsonArray();
		fields.add(field("Player", record.name(), true));
		fields.add(field("Confidence", record.confidence().label().toUpperCase(Locale.ROOT), true));
		fields.add(field("Distance", record.distance() < 0
				? "unknown"
				: String.format(Locale.ROOT, "%.1f blocks", record.distance()), true));
		fields.add(field("Server", record.serverHost(), true));
		fields.add(field("UUID", "`" + record.uuid() + "`", false));
		fields.add(field("Position", "`" + record.positionText() + "`", true));

		StringBuilder evidence = new StringBuilder();
		for (Observation observation : record.signals()) {
			if (evidence.length() > 0) {
				evidence.append('\n');
			}
			evidence.append("• ").append(observation.kind().label());
			if (!observation.evidence().isEmpty()) {
				evidence.append(": ").append(observation.evidence());
			}
			evidence.append(" (tick ").append(observation.tick()).append(')');
		}
		fields.add(field("Signals", evidence.length() == 0 ? "none" : trim(evidence.toString(), MAX_FIELD), false));
		embed.add("fields", fields);

		JsonObject footer = new JsonObject();
		footer.addProperty("text", "capy client · anti admin");
		embed.add("footer", footer);

		JsonObject payload = new JsonObject();
		payload.addProperty("username", "Capy Client");
		JsonArray embeds = new JsonArray();
		embeds.add(embed);
		payload.add("embeds", embeds);
		return payload;
	}

	private static JsonObject field(String name, String value, boolean inline) {
		JsonObject field = new JsonObject();
		field.addProperty("name", name);
		field.addProperty("value", value == null || value.isEmpty() ? "—" : trim(value, MAX_FIELD));
		field.addProperty("inline", inline);
		return field;
	}

	/** A title that says what happened, not just that something did. */
	private static String title(AlertRecord record) {
		String who = record.name();
		Minecraft mc = Minecraft.getInstance();
		if (mc.getConnection() != null && record.uuid() != null && "unknown".equals(record.name())) {
			PlayerInfo info = mc.getConnection().getPlayerInfo(record.uuid());
			if (info != null && info.getProfile() != null && info.getProfile().name() != null) {
				who = info.getProfile().name();
			}
		}
		return switch (record.kind()) {
			case PROBED -> "🚨 " + who + " is online and hidden";
			case GHOST -> "🚨 " + who + " vanished but is still here";
			case VANISHED -> "🚨 " + who + " has vanished";
			case SPECTATOR -> "🚨 " + who + " switched to spectator";
			case HIDDEN_TAG -> "🚨 " + who + " removed their nametag";
			case UNSEEN -> "🚨 someone is nearby but unseen";
			case NEARBY -> "🚨 " + who + " is nearby";
			case JOINED -> "🚨 " + who + " joined the server";
		};
	}

	/** Keeps a field inside Discord's limit without cutting a word in half. */
	private static String trim(String text, int max) {
		if (text.length() <= max) {
			return text;
		}
		return text.substring(0, max - 1) + "…";
	}

	/** Accepts only an absolute http(s) URL; anything else disables the hook. */
	private static String sanitize(String raw) {
		if (raw == null) {
			return null;
		}
		String value = raw.trim();
		if (value.isEmpty()) {
			return null;
		}
		try {
			URI uri = URI.create(value);
			String scheme = uri.getScheme();
			if (scheme == null || uri.getHost() == null) {
				return null;
			}
			if (!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http")) {
				return null;
			}
			return uri.toString();
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
