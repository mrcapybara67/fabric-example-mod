package com.capy.client.module.modules.antiadmin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;

/**
 * Several removal packets for one account inside a few milliseconds.
 *
 * A vanish that is done properly is a sequence: unlist, clear teams, drop the
 * entity, drop the info entry. Written naively by a plugin, that sequence
 * arrives as one burst for one player, which is a shape no normal disconnect
 * produces — a quit is a single packet.
 *
 * The window is deliberately tiny (10 ms). A busy server removing twenty
 * players after a restart would arrive spread over dozens of ticks, while a
 * vanish burst is written to the socket in one go.
 */
public final class RapidPacketDetector extends AbstractDetector {
	private static final int MAX_IN_WINDOW = 3;
	private static final long WINDOW_MS = 10L;

	private final Map<UUID, Deque<Long>> arrivals = new HashMap<>();

	public RapidPacketDetector(SignalBuffer buffer) {
		super(buffer);
	}

	@Override
	public String name() {
		return "rapid_packet_cluster";
	}

	@Override
	public DetectionKind kind() {
		return DetectionKind.RAPID_PACKET_CLUSTER;
	}

	@Override
	public void reset() {
		arrivals.clear();
	}

	@Override
	public void onPacket(PacketSignals.Event event) {
		if (event instanceof PacketSignals.InfoRemove remove) {
			arrived(remove.uuid(), remove.timeMs(), "info entry removed");
		} else if (event instanceof PacketSignals.TeamRemove team) {
			Minecraft mc = Minecraft.getInstance();
			for (String name : team.names()) {
				var info = TabList.byName(mc, name);
				if (info != null && info.getProfile() != null) {
					arrived(info.getProfile().id(), team.timeMs(), "removed from team");
				}
			}
		} else if (event instanceof PacketSignals.EntitiesRemoved removed) {
			for (UUID id : removed.uuids()) {
				arrived(id, removed.timeMs(), "entity despawned");
			}
		}
	}

	private void arrived(UUID subject, long time, String what) {
		if (subject == null) {
			return;
		}
		Deque<Long> times = arrivals.computeIfAbsent(subject, key -> new ArrayDeque<>());
		while (!times.isEmpty() && time - times.peekFirst() > WINDOW_MS) {
			times.pollFirst();
		}
		times.addLast(time);
		if (times.size() >= MAX_IN_WINDOW) {
			int count = times.size();
			times.clear();
			emit(subject, null, count + " removal packets in one burst, last one: " + what);
		}
	}
}
