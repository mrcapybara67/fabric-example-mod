package com.capy.client.module.modules.antiadmin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;

/**
 * An unlisted account whose latency keeps changing.
 *
 * A latency value is not a stored number — the server measures the round trip
 * to that connection and sends the result. So an entry that is hidden from the
 * tab list but still reporting fresh, changing latency is not a stale record
 * left behind by a plugin: it is a connection the server is still talking to,
 * owned by somebody the client is not allowed to see.
 *
 * This is the signal that survives a vanish so thorough that even the probe
 * list is cleaned; it costs nothing, because it only reads values the client
 * already receives.
 */
public final class PingGhostDetector extends AbstractDetector {
	/** One report per account per window; a ping changes constantly. */
	private static final long COOLDOWN_MS = 10_000L;

	private final Map<UUID, Integer> lastLatency = new HashMap<>();
	private final Map<UUID, Long> lastReport = new HashMap<>();

	public PingGhostDetector(SignalBuffer buffer) {
		super(buffer);
	}

	@Override
	public String name() {
		return "phantom_ping";
	}

	@Override
	public DetectionKind kind() {
		return DetectionKind.PHANTOM_PING;
	}

	@Override
	public void reset() {
		lastLatency.clear();
		lastReport.clear();
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!enabled() || mc.getConnection() == null) {
			return;
		}
		ClientPacketListener connection = mc.getConnection();
		long now = System.currentTimeMillis();
		for (PlayerInfo info : connection.getOnlinePlayers()) {
			if (info.getProfile() == null || info.getProfile().id() == null) {
				continue;
			}
			UUID id = info.getProfile().id();
			if (TabList.listed(mc, id)) {
				lastLatency.remove(id);
				continue;
			}
			int latency = info.getLatency();
			Integer previous = lastLatency.put(id, latency);
			if (previous == null || previous == latency) {
				continue;
			}
			Long reported = lastReport.get(id);
			if (reported != null && now - reported < COOLDOWN_MS) {
				continue;
			}
			lastReport.put(id, now);
			String name = info.getProfile().name();
			emit(id, null, "unlisted account still reports live latency ("
					+ previous + " → " + latency + " ms)"
					+ (name == null ? "" : " as " + name));
		}
	}
}
