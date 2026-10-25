package com.capy.client.module.modules.antiadmin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.level.GameType;

/**
 * A listed player whose game mode became spectator.
 *
 * Spectator is how a modern vanish hides staff: the account stays connected,
 * stays in the tab list and keeps its entry, but no entity is sent to survival
 * players, so nothing in the world can reveal them. The game mode in the player
 * info packet is the only place the client is told.
 *
 * A switched game mode is only reported once per account per session, and only
 * when it changes to spectator from something else: a staff member hopping in
 * and out of spectator all evening must not produce a card each time.
 */
public final class GamemodeDetector extends AbstractDetector {
	private static final int SPECTATOR = GameType.SPECTATOR.getId();

	private final Map<UUID, Integer> modes = new HashMap<>();
	private final java.util.Set<UUID> reported = new java.util.HashSet<>();

	public GamemodeDetector(SignalBuffer buffer) {
		super(buffer);
	}

	@Override
	public String name() {
		return "spectator_cycle";
	}

	@Override
	public DetectionKind kind() {
		return DetectionKind.SPECTATOR_CYCLE;
	}

	@Override
	public void reset() {
		modes.clear();
		reported.clear();
	}

	@Override
	public void onPacket(PacketSignals.Event event) {
		if (!(event instanceof PacketSignals.InfoUpdate update) || update.gameMode() == null) {
			return;
		}
		int mode = update.gameMode();
		Integer previous = modes.put(update.uuid(), mode);
		if (mode != SPECTATOR || reported.contains(update.uuid())) {
			return;
		}
		if (previous != null && previous == SPECTATOR) {
			return;
		}
		reported.add(update.uuid());
		emit(update.uuid(), null, "switched to spectator while listed and connected"
				+ (previous == null ? "" : " (from mode " + previous + ")"));
	}
}
