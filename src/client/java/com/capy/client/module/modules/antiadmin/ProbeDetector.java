package com.capy.client.module.modules.antiadmin;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;

/**
 * Asks the server, in its own words, whether a hidden account is online.
 *
 * The request is a tab completion for the argument of a private message, which
 * the server answers with the names it will accept there. That list is built
 * from the connected players, so a vanished account still appears in it while
 * the tab list does not. Nothing is sent as chat and nothing is executed: the
 * request is exactly what the client sends while a person is typing a command,
 * which is what makes it invisible to a plugin watching for probes.
 *
 * Stealth rules, all of them learned from what a plugin could log:
 * <ul>
 *   <li>one request in flight, and one per name every {@link #COOLDOWN_MS};</li>
 *   <li>a jittered 40–120 tick gap between requests, because a metronome is
 *       the easiest thing to fingerprint;</li>
 *   <li>the command alias rotates, so the log never shows one verb in a row;</li>
 *   <li>the argument is a short prefix of the name, which is what a person
 *       typing would produce and all the completer needs.</li>
 * </ul>
 */
public final class ProbeDetector extends AbstractDetector {
	private static final String[] ALIASES = {"msg", "tell", "w", "whisper", "m"};
	private static final long COOLDOWN_MS = 30_000L;
	private static final int MIN_GAP_TICKS = 40;
	private static final int MAX_GAP_TICKS = 120;
	private static final int TIMEOUT_TICKS = 100;
	private static final int MAX_QUEUE = 16;

	private final Set<String> asked = new HashSet<>();
	private final Map<String, Long> lastAskedAt = new HashMap<>();
	private final ArrayDeque<String> queue = new ArrayDeque<>();
	private int aliasIndex;
	private long nextAllowedTick;
	private int probeId = -1;
	private String probeName;
	private long probeSentTick;

	public ProbeDetector(SignalBuffer buffer) {
		super(buffer);
	}

	@Override
	public String name() {
		return "probe";
	}

	@Override
	public DetectionKind kind() {
		return DetectionKind.PROBE_CONFIRMED;
	}

	@Override
	public void reset() {
		asked.clear();
		lastAskedAt.clear();
		queue.clear();
		aliasIndex = 0;
		nextAllowedTick = 0;
		probeId = -1;
		probeName = null;
	}

	/** Queues a name for probing. Called when a vanish is suspected. */
	public void enqueue(String name) {
		if (name == null || name.length() < 3 || queue.size() >= MAX_QUEUE) {
			return;
		}
		String key = name.toLowerCase(Locale.ROOT);
		if (asked.contains(key)) {
			return;
		}
		for (String queued : queue) {
			if (queued.equalsIgnoreCase(name)) {
				return;
			}
		}
		// The original spelling is kept: the server accepts any case, but the
		// client's own name lookups are exact, and the alert has to print the
		// name the way it is really spelled.
		queue.addLast(name);
	}

	@Override
	public void onPacket(PacketSignals.Event event) {
		if (!(event instanceof PacketSignals.Suggestions suggestions) || probeId < 0
				|| suggestions.id() != probeId) {
			return;
		}
		String name = probeName;
		probeId = -1;
		probeName = null;
		if (name == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		for (String candidate : suggestions.names()) {
			if (!candidate.equalsIgnoreCase(name)) {
				continue;
			}
			UUID subject = uuidOf(mc, name);
			// A hidden account has no entry left to look the name up from, so
			// the subject carries the name the server just confirmed.
			SignalBuffer.nameSubject(subject, name);
			if (!TabList.listed(mc, subject)) {
				emit(subject, null, "the server completes \"" + name + "\" but the tab list hides it");
			}
			return;
		}
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!enabled() || mc.level == null || mc.player == null) {
			return;
		}
		long now = mc.level.getGameTime();
		if (probeId >= 0) {
			if (now - probeSentTick > TIMEOUT_TICKS) {
				probeId = -1;
				probeName = null;
			}
			return;
		}
		if (now < nextAllowedTick) {
			return;
		}
		collectCandidates(mc);
		String name = queue.pollFirst();
		if (name == null) {
			return;
		}
		String command = ALIASES[aliasIndex++ % ALIASES.length] + " "
				+ name.substring(0, Math.min(3, name.length()));
		int id = PacketSignals.probe(command);
		if (id < 0) {
			return;
		}
		String key = name.toLowerCase(Locale.ROOT);
		asked.add(key);
		lastAskedAt.put(key, System.currentTimeMillis());
		probeId = id;
		probeName = name;
		probeSentTick = now;
		nextAllowedTick = now + ThreadLocalRandom.current().nextInt(MIN_GAP_TICKS, MAX_GAP_TICKS);
	}

	/**
	 * Adds every account the client holds but the tab list does not draw.
	 *
	 * This is the case the probe exists for, so it is collected every time a
	 * request slot frees up rather than only when a removal packet arrives.
	 */
	private void collectCandidates(Minecraft mc) {
		ClientPacketListener connection = mc.getConnection();
		if (connection == null) {
			return;
		}
		for (PlayerInfo info : connection.getOnlinePlayers()) {
			if (info.getProfile() == null || info.getProfile().name() == null) {
				continue;
			}
			UUID id = info.getProfile().id();
			if (id == null || TabList.listed(mc, id)) {
				continue;
			}
			String name = info.getProfile().name();
			Long last = lastAskedAt.get(name.toLowerCase(Locale.ROOT));
			if (last != null && System.currentTimeMillis() - last < COOLDOWN_MS) {
				continue;
			}
			enqueue(name);
		}
	}

	private static UUID uuidOf(Minecraft mc, String name) {
		PlayerInfo info = TabList.byName(mc, name);
		if (info != null && info.getProfile() != null && info.getProfile().id() != null) {
			return info.getProfile().id();
		}
		// A fully hidden account has no entry left, so the name is the only
		// identity there is. A stable derived uuid keeps its cards consistent.
		return UUID.nameUUIDFromBytes(("capy:name:" + name.toLowerCase(Locale.ROOT))
				.getBytes(StandardCharsets.UTF_8));
	}
}
