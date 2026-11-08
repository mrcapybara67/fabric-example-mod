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
 * Only server system messages are evidence. A player can type a vanish line
 * or a staff member's name, so player chat is deliberately ignored. The one
 * text signal kept here is {@code VANISH_TEXT}: a server or plugin line that
 * announces a vanish. It is weak evidence and heavily rate limited; anarchy
 * profiles turn it off because their chat is a firehose of faked lines.
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
		if (event instanceof PacketSignals.SystemChat system) {
			handleSystemChat(system.text());
		}
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

	private static String quote(String text) {
		if (text == null) {
			return "";
		}
		String flat = text.replace('\n', ' ').trim();
		return flat.length() <= MAX_QUOTE ? flat : flat.substring(0, MAX_QUOTE) + "…";
	}
}
