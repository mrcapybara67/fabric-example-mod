package com.capy.client.module.modules.antiadmin;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;

/**
 * Two chat signals that point in opposite directions.
 *
 * {@code HEADLESS_CHAT} is a signed message from an account the client has no
 * entity for <em>and</em> that the tab list does not draw. Both halves are
 * needed. Chat is broadcast to every player on the server while entities are
 * only sent within tracking range, so "no entity" on its own describes every
 * person talking from across the map - reporting that made a busy server fire
 * the loudest alert in the module at a stranger's chatter. No entity and no
 * tab entry together are the actual contradiction: the server is relaying a
 * signed message from a session it will not admit exists.
 *
 * {@code VANISH_TEXT} is the opposite: a line that announces a vanish. It comes
 * from the server, from a plugin, or from another player typing it, so it is
 * weak evidence and heavily rate limited — on an anarchy server it is noise,
 * which is why those profiles turn it off.
 */
public final class ChatDetector extends AbstractDetector {
	/** How long one name can produce vanish wording before it is ignored. */
	private static final long VANISH_COOLDOWN_MS = 10_000L;
	private static final int MAX_QUOTE = 60;
	private static final List<String> VANISH_WORDS = List.of(
			"vanished", "vanish", "is now invisible", "left silently", "went invisible", "now hidden");

	private final Map<String, Long> lastVanish = new HashMap<>();

	public ChatDetector(SignalBuffer buffer) {
		super(buffer);
	}

	@Override
	public String name() {
		return "headless_chat";
	}

	@Override
	public DetectionKind kind() {
		return DetectionKind.HEADLESS_CHAT;
	}

	@Override
	public void reset() {
		lastVanish.clear();
	}

	@Override
	public void onPacket(PacketSignals.Event event) {
		if (event instanceof PacketSignals.Chat chat) {
			handlePlayerChat(chat);
		} else if (event instanceof PacketSignals.SystemChat system) {
			handleSystemChat(system.text());
		}
	}

	private void handlePlayerChat(PacketSignals.Chat chat) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || chat.sender() == null || chat.sender().equals(localId(mc))) {
			return;
		}
		String quote = quote(chat.text());
		// Being out of entity range is normal for somebody on the other side of
		// the server; being out of entity range *and* unlisted is not.
		if (mc.level.getPlayerByUUID(chat.sender()) == null && !TabList.listed(mc, chat.sender())) {
			emitAs(DetectionKind.HEADLESS_CHAT, chat.sender(), null,
					"chat from an account with no entity and no tab entry: \"" + quote + "\"");
		}
		scanForVanish(chat.sender(), chat.text());
	}

	private void handleSystemChat(String text) {
		if (text == null || text.isEmpty()) {
			return;
		}
		scanForVanish(null, text);
	}

	/**
	 * Reports vanish wording, attributed to an account when the line names one.
	 *
	 * The name is found by asking the tab list about each word in the line; a
	 * line with no known name in it is not attributed at all, because guessing
	 * would put a stranger's words on a watched player's card.
	 */
	private void scanForVanish(UUID sender, String text) {
		if (text == null) {
			return;
		}
		String lower = text.toLowerCase(Locale.ROOT);
		boolean announces = false;
		for (String word : VANISH_WORDS) {
			if (lower.contains(word)) {
				announces = true;
				break;
			}
		}
		if (!announces) {
			return;
		}
		UUID subject = sender;
		if (subject == null) {
			subject = firstKnownName(text);
		}
		if (subject == null) {
			return;
		}
		long now = System.currentTimeMillis();
		String key = subject.toString();
		Long previous = lastVanish.get(key);
		if (previous != null && now - previous < VANISH_COOLDOWN_MS) {
			return;
		}
		lastVanish.put(key, now);
		emitAs(DetectionKind.VANISH_TEXT, subject, null, "chat says: \"" + quote(text) + "\"");
	}

	/** The first word of the line that the client knows as an account. */
	private static UUID firstKnownName(String text) {
		Minecraft mc = Minecraft.getInstance();
		for (String word : text.split("[^A-Za-z0-9_]+")) {
			if (word.length() < 3) {
				continue;
			}
			var info = TabList.byNameIgnoreCase(mc, word);
			if (info != null && info.getProfile() != null && info.getProfile().id() != null) {
				return info.getProfile().id();
			}
		}
		return null;
	}

	private static UUID localId(Minecraft mc) {
		return mc.player == null ? null : mc.player.getUUID();
	}

	private static String quote(String text) {
		if (text == null) {
			return "";
		}
		String flat = text.replace('\n', ' ').trim();
		return flat.length() <= MAX_QUOTE ? flat : flat.substring(0, MAX_QUOTE) + "…";
	}
}
