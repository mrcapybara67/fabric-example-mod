package com.capy.client.module.modules.antiadmin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;

/**
 * The gap between the two player lists the client keeps.
 *
 * {@code getOnlinePlayers()} is every entry the connection holds;
 * {@code getListedOnlinePlayers()} is the subset the tab list draws. A normal
 * client has the same account in both. A server that vanished somebody clears
 * the listed flag and leaves the entry, which is why the two sets diverge — and
 * that divergence is the rawest form of the whole feature.
 *
 * Joining produces a transient mismatch too: the entry can arrive a tick before
 * the listed update. Requiring five stable ticks costs nothing and removes that
 * false positive entirely.
 *
 * The detector deliberately does not read the packet that caused the change. It
 * compares the state the game ended up in, so a plugin that hides a player
 * through a path nobody thought to hook is still caught.
 */
public final class ShadowMapDetector extends AbstractDetector {
	private static final int CONFIRM_TICKS = 5;

	private final Map<UUID, Integer> hidden = new HashMap<>();
	private final Set<UUID> reported = new HashSet<>();

	public ShadowMapDetector(SignalBuffer buffer) {
		super(buffer);
	}

	@Override
	public String name() {
		return "shadow_map_delta";
	}

	@Override
	public DetectionKind kind() {
		return DetectionKind.SHADOW_MAP_DELTA;
	}

	@Override
	public void reset() {
		hidden.clear();
		reported.clear();
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!enabled() || mc.getConnection() == null) {
			return;
		}
		ClientPacketListener connection = mc.getConnection();
		Set<UUID> listed = TabList.listed(mc);
		Set<UUID> seen = new HashSet<>();
		for (PlayerInfo info : connection.getOnlinePlayers()) {
			if (info.getProfile() == null || info.getProfile().id() == null) {
				continue;
			}
			UUID id = info.getProfile().id();
			seen.add(id);
			if (listed.contains(id) || reported.contains(id)) {
				continue;
			}
			int streak = hidden.merge(id, 1, Integer::sum);
			if (streak >= CONFIRM_TICKS) {
				reported.add(id);
				String name = info.getProfile().name();
				emit(id, null, "player info entry is held but the tab list does not draw it"
						+ (name == null ? "" : " (" + name + ")"));
			}
		}
		hidden.keySet().retainAll(seen);
	}
}
