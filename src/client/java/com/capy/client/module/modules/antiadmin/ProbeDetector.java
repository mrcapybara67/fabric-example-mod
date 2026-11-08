package com.capy.client.module.modules.antiadmin;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
 * <p>The request is a tab completion for the argument of a command, which the
 * server answers with the names it will accept there. That list is built from
 * connected players, so a vanished account can still appear in it while the tab
 * list does not. Nothing is sent as chat and nothing is executed.</p>
 *
 * <h2>Calibration</h2>
 * A command is not assumed to leak. On each join, every candidate alias is
 * tried with the prefix of a visible player; only aliases whose reply contains
 * that visible player are kept. A server that answers from a command list or a
 * different permission set therefore does not turn a negative reply into
 * \"offline\". The set is rebuilt on a world change.
 *
 * <h2>Footprint</h2>
 * There is one request in flight. A name is attempted at most once every 30 to
 * 60 seconds, with per-name jitter, and the whole detector is hard capped at
 * {@link #MAX_PER_MINUTE} requests in any rolling minute. Probing waits ten
 * seconds after joining and does nothing while the watchlist is empty. Two
 * unanswered requests in a row are treated as rate limiting and back the
 * detector off for a minute. These are deliberately conservative: a probe is
 * the only part of Anti Admin that sends anything to the server.
 */
public final class ProbeDetector extends AbstractDetector {
	/** Aliases that can take a player name as their first argument. */
	private static final String[] ALIASES = {
			"msg", "tell", "w", "whisper", "m", "tpa", "tpahere", "tpaccept"
	};
	private static final long MIN_COOLDOWN_MS = 30_000L;
	private static final long MAX_COOLDOWN_MS = 60_000L;
	private static final int MAX_PER_MINUTE = 4;
	private static final int STARTUP_GRACE_TICKS = 200;
	private static final int MIN_GAP_TICKS = 40;
	private static final int MAX_GAP_TICKS = 120;
	private static final int TIMEOUT_TICKS = 100;
	private static final int MAX_QUEUE = 64;
	private static final int MAX_STATES = 128;

	/** Receives the end of a completed probe cycle. */
	public interface Listener {
		/** Every working command was tried and none returned the name. */
		void onAbsent(String name);
	}

	private final Set<String> workingAliases = new LinkedHashSet<>();
	private final ArrayDeque<String> queue = new ArrayDeque<>();
	private final ArrayDeque<Long> requestTimes = new ArrayDeque<>();
	private final Map<String, Long> nextAttemptAt = new HashMap<>();
	private final Map<String, Integer> attemptedAliases = new HashMap<>();
	private final Map<String, ProbeState> states = new HashMap<>();
	private final Map<String, Long> stateAt = new HashMap<>();

	private boolean watchlistPresent;
	private Listener listener = name -> {
	};

	private int calibrationIndex;
	private String calibrationTarget;
	private String calibrationAlias;
	private int calibrationId = -1;
	private long calibrationSentTick;
	private long calibrationNextTick;
	private boolean calibrated;

	private int probeId = -1;
	private String probeName;
	private String probeAlias;
	private int checkId = -1;
	private String checkName;
	private String checkAlias;
	private String checkTarget;
	private long probeSentTick;
	private long nextAllowedTick;
	private long backoffUntilTick;
	private int timeouts;
	private long sessionStartTick = -1L;

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

	/** Wires the module's cleanup callback. */
	public void listener(Listener listener) {
		this.listener = listener == null ? name -> {
		} : listener;
	}

	/** The module sets this every tick; calibration also waits for a list. */
	public void setWatchlistPresent(boolean present) {
		if (watchlistPresent != present) {
			watchlistPresent = present;
			if (!present) {
				queue.clear();
			}
		}
	}

	@Override
	public void reset() {
		workingAliases.clear();
		queue.clear();
		requestTimes.clear();
		nextAttemptAt.clear();
		attemptedAliases.clear();
		states.clear();
		stateAt.clear();
		calibrationIndex = 0;
		calibrationTarget = null;
		calibrationAlias = null;
		calibrationId = -1;
		calibrationSentTick = 0L;
		calibrationNextTick = 0L;
		calibrated = false;
		probeId = -1;
		probeName = null;
		probeAlias = null;
		checkId = -1;
		checkName = null;
		checkAlias = null;
		checkTarget = null;
		probeSentTick = 0L;
		nextAllowedTick = 0L;
		backoffUntilTick = 0L;
		timeouts = 0;
		sessionStartTick = -1L;
	}

	/**
	 * Queues a name. The cooldown is applied when a request is chosen, not
	 * here, so a name stays eligible for the next cycle instead of being
	 * rejected for the rest of the session.
	 */
	public void enqueue(String name) {
		if (name == null || name.length() < 3 || queue.size() >= MAX_QUEUE) {
			return;
		}
		String key = name.toLowerCase(Locale.ROOT);
		for (String queued : queue) {
			if (queued.equalsIgnoreCase(key)) {
				return;
			}
		}
		queue.addLast(key);
	}

	/** What the last completed cycle proved about a name. */
	public ProbeState stateFor(String name) {
		if (name == null) {
			return ProbeState.UNKNOWN;
		}
		return states.getOrDefault(name.toLowerCase(Locale.ROOT), ProbeState.UNKNOWN);
	}

	/** How old the last completed cycle is, or -1 when there is none. */
	public long stateAgeMs(String name) {
		if (name == null) {
			return -1L;
		}
		Long at = stateAt.get(name.toLowerCase(Locale.ROOT));
		return at == null ? -1L : Math.max(0L, System.currentTimeMillis() - at);
	}

	@Override
	public void onPacket(PacketSignals.Event event) {
		if (!(event instanceof PacketSignals.Suggestions suggestions)) {
			return;
		}
		if (checkId >= 0 && suggestions.id() == checkId) {
			onCheckReply(suggestions.names());
			return;
		}
		if (calibrationId >= 0 && suggestions.id() == calibrationId) {
			onCalibrationReply(suggestions.names());
			return;
		}
		if (probeId < 0 || suggestions.id() != probeId) {
			return;
		}
		onProbeReply(suggestions.names());
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!enabled() || mc.level == null || mc.player == null) {
			return;
		}
		long now = mc.level.getGameTime();
		if (sessionStartTick < 0L) {
			sessionStartTick = now;
		}
		if (!watchlistPresent || now - sessionStartTick < STARTUP_GRACE_TICKS) {
			return;
		}
		if (!calibrated) {
			calibrate(mc, now);
			return;
		}
		if (workingAliases.isEmpty()) {
			return;
		}
		if (now < backoffUntilTick) {
			return;
		}
		if (checkId >= 0) {
			if (now - probeSentTick > TIMEOUT_TICKS) {
				// A silent revalidation means the command is no longer trustworthy.
				workingAliases.remove(checkAlias);
				checkId = -1;
				checkName = null;
				checkAlias = null;
				checkTarget = null;
				timeouts++;
				if (timeouts >= 2) {
					backoffUntilTick = now + 20L * 60L;
				}
			}
			return;
		}
		if (probeId >= 0) {
			if (now - probeSentTick > TIMEOUT_TICKS) {
				timeoutProbe(now);
			}
			return;
		}
		if (now < nextAllowedTick) {
			return;
		}
		long nowMs = System.currentTimeMillis();
		if (!requestAllowed(nowMs)) {
			return;
		}
		String name = nextReadyName(nowMs);
		if (name == null) {
			return;
		}
		String alias = nextAlias(name);
		int id = PacketSignals.probe(alias + " " + prefix(name));
		if (id < 0) {
			return;
		}
		recordRequest(nowMs);
		String key = name.toLowerCase(Locale.ROOT);
		nextAttemptAt.put(key, nowMs + randomCooldownMs());
		probeId = id;
		probeName = name;
		probeAlias = alias;
		probeSentTick = now;
		nextAllowedTick = now + ThreadLocalRandom.current().nextInt(MIN_GAP_TICKS, MAX_GAP_TICKS);
	}

	// ------------------------------------------------------------- calibration

	private void calibrate(Minecraft mc, long now) {
		if (calibrationTarget == null) {
			calibrationTarget = visibleName(mc);
			calibrationNextTick = now;
		}
		if (calibrationTarget == null) {
			return;
		}
		if (calibrationId >= 0) {
			if (now - calibrationSentTick > TIMEOUT_TICKS) {
				calibrationId = -1;
				calibrationAlias = null;
				calibrationIndex++;
				calibrationNextTick = now + 20L;
				timeouts++;
				if (calibrationIndex >= ALIASES.length) {
					calibrated = true;
				}
				if (timeouts >= 2) {
					backoffUntilTick = now + 20L * 60L;
				}
			}
			return;
		}
		if (now < calibrationNextTick) {
			return;
		}
		long nowMs = System.currentTimeMillis();
		if (!requestAllowed(nowMs)) {
			return;
		}
		if (calibrationIndex >= ALIASES.length) {
			calibrated = true;
			return;
		}
		String alias = ALIASES[calibrationIndex];
		int id = PacketSignals.probe(alias + " " + prefix(calibrationTarget));
		if (id < 0) {
			return;
		}
		recordRequest(nowMs);
		calibrationAlias = alias;
		calibrationId = id;
		calibrationSentTick = now;
		calibrationNextTick = now + ThreadLocalRandom.current().nextInt(20, 61);
	}

	private void onCalibrationReply(List<String> names) {
		timeouts = 0;
		if (calibrationAlias != null && containsIgnoreCase(names, calibrationTarget)) {
			workingAliases.add(calibrationAlias);
		}
		calibrationAlias = null;
		calibrationId = -1;
		calibrationIndex++;
		if (calibrationIndex >= ALIASES.length) {
			calibrated = true;
		}
	}

	private static String visibleName(Minecraft mc) {
		if (mc == null || mc.getConnection() == null) {
			return null;
		}
		for (PlayerInfo info : mc.getConnection().getListedOnlinePlayers()) {
			if (info != null && info.getProfile() != null) {
				String name = info.getProfile().name();
				if (name != null && name.length() >= 3) {
					return name;
				}
			}
		}
		return null;
	}

	// --------------------------------------------------------------- probing

	private String nextReadyName(long nowMs) {
		int count = queue.size();
		for (int i = 0; i < count; i++) {
			String name = queue.pollFirst();
			if (name == null) {
				return null;
			}
			Long next = nextAttemptAt.get(name);
			if (next != null && nowMs < next) {
				queue.addLast(name);
				continue;
			}
			return name;
		}
		return null;
	}

	private String nextAlias(String name) {
		List<String> aliases = new ArrayList<>(workingAliases);
		int attempts = attemptedAliases.getOrDefault(name.toLowerCase(Locale.ROOT), 0);
		return aliases.get(attempts % aliases.size());
	}

	private void onProbeReply(List<String> names) {
		String name = probeName;
		String alias = probeAlias;
		probeId = -1;
		probeName = null;
		probeAlias = null;
		if (name == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		// A successful reply means the server is answering, so a previous
		// timeout was not a lasting rate limit.
		timeouts = 0;
		if (containsIgnoreCase(names, name)) {
			UUID subject = uuidOf(mc, name);
			SignalBuffer.nameSubject(subject, name);
			String key = name.toLowerCase(Locale.ROOT);
			attemptedAliases.remove(key);
			if (TabList.listed(mc, subject)) {
				states.remove(key);
				stateAt.remove(key);
			} else {
				setState(key, ProbeState.HIDDEN);
				String evidence = "the server completes \"" + name
						+ "\" but the tab list hides it";
				emit(subject, null, evidence);
			}
			return;
		}
		String key = name.toLowerCase(Locale.ROOT);
		int attempts = attemptedAliases.merge(key, 1, Integer::sum);
		if (attempts < workingAliases.size()) {
			return;
		}
		// Before trusting a negative, check that the alias still answers for a
		// visible player. A command that stopped leaking would otherwise make
		// every hidden name look offline.
		long nowMs = System.currentTimeMillis();
		String target = visibleName(mc);
		if (alias != null && target != null && requestAllowed(nowMs)) {
			int id = PacketSignals.probe(alias + " " + prefix(target));
			if (id >= 0) {
				recordRequest(nowMs);
				checkId = id;
				probeSentTick = mc.level.getGameTime();
				checkName = name;
				checkAlias = alias;
				checkTarget = target;
				return;
			}
		}
		markAbsent(name);
	}

	private void onCheckReply(List<String> names) {
		String name = checkName;
		String alias = checkAlias;
		String target = checkTarget;
		checkId = -1;
		checkName = null;
		checkAlias = null;
		checkTarget = null;
		if (alias == null || target == null) {
			return;
		}
		if (containsIgnoreCase(names, target)) {
			markAbsent(name);
		} else {
			// Drop the command and retry the name with the rest of the set.
			workingAliases.remove(alias);
			if (name != null) {
				attemptedAliases.remove(name.toLowerCase(Locale.ROOT));
			}
		}
	}

	private void markAbsent(String name) {
		attemptedAliases.remove(name.toLowerCase(Locale.ROOT));
		setState(name.toLowerCase(Locale.ROOT), ProbeState.ABSENT);
		buffer.clearName(name);
		listener.onAbsent(name);
	}

	private void setState(String key, ProbeState state) {
		if (states.size() >= MAX_STATES && !states.containsKey(key)) {
			String oldest = null;
			long oldestAt = Long.MAX_VALUE;
			for (String tracked : states.keySet()) {
				long at = stateAt.getOrDefault(tracked, 0L);
				if (at < oldestAt) {
					oldestAt = at;
					oldest = tracked;
				}
			}
			if (oldest != null) {
				states.remove(oldest);
				stateAt.remove(oldest);
			}
		}
		states.put(key, state);
		stateAt.put(key, System.currentTimeMillis());
	}

	private void timeoutProbe(long now) {
		probeId = -1;
		probeName = null;
		timeouts++;
		if (timeouts >= 2) {
			// Two silent requests are the only rate-limit signal a client can
			// observe. Back off for a minute rather than retrying into it.
			backoffUntilTick = now + 20L * 60L;
		} else {
			nextAllowedTick = now + 100L;
		}
	}

	private boolean requestAllowed(long nowMs) {
		while (!requestTimes.isEmpty() && nowMs - requestTimes.peekFirst() >= 60_000L) {
			requestTimes.pollFirst();
		}
		return requestTimes.size() < MAX_PER_MINUTE;
	}

	private void recordRequest(long nowMs) {
		requestTimes.addLast(nowMs);
	}

	private static long randomCooldownMs() {
		return ThreadLocalRandom.current().nextLong(MIN_COOLDOWN_MS, MAX_COOLDOWN_MS + 1L);
	}

	/** Name length minus one, but never fewer than four characters. */
	private static String prefix(String name) {
		int take = Math.min(name.length(), Math.max(4, name.length() - 1));
		return name.substring(0, take);
	}

	private static boolean containsIgnoreCase(List<String> names, String target) {
		if (target == null) {
			return false;
		}
		for (String name : names) {
			if (name != null && name.equalsIgnoreCase(target)) {
				return true;
			}
		}
		return false;
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
