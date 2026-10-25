package com.capy.client.module.modules.antiadmin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;

/**
 * The strongest quiet signal there is: a player entity the client is drawing
 * whose account the tab list does not list.
 *
 * A vanish plugin normally removes the entity as well, so what this catches is
 * the failure mode — the tab entry goes first, the entity is left behind for a
 * tick or two, or the plugin only clears the listed flag and never despawns the
 * body. Either way the client is holding a player it is not allowed to know
 * about, which is exactly the thing worth reporting.
 *
 * Three consecutive ticks are required, because a join sends the entity and the
 * tab entry in either order and one tick of mismatch is normal. An account the
 * {@link NpcFingerprinter} has called a stand-in is ignored: a static hologram
 * with a player skin is not a vanished administrator.
 */
public final class EntityTabCrossRefDetector extends AbstractDetector {
	private static final int CONFIRM_TICKS = 3;

	private final Set<UUID> npcs;
	private final Map<UUID, Integer> misses = new HashMap<>();
	private final Set<UUID> reported = new HashSet<>();

	public EntityTabCrossRefDetector(SignalBuffer buffer, Set<UUID> npcs) {
		super(buffer);
		this.npcs = npcs;
	}

	@Override
	public String name() {
		return "entity_tab_cross_ref";
	}

	@Override
	public DetectionKind kind() {
		return DetectionKind.ENTITY_NOT_IN_TAB;
	}

	@Override
	public void reset() {
		misses.clear();
		reported.clear();
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!enabled()) {
			return;
		}
		if (mc == null || mc.level == null || mc.player == null) {
			misses.clear();
			return;
		}
		Set<UUID> listed = TabList.listed(mc);
		for (AbstractClientPlayer player : mc.level.players()) {
			if (player == null || player == mc.player || player.isRemoved()) {
				continue;
			}
			UUID id = player.getUUID();
			if (listed.contains(id)) {
				misses.remove(id);
				continue;
			}
			if (npcs.contains(id) || reported.contains(id)) {
				continue;
			}
			int streak = misses.merge(id, 1, Integer::sum);
			if (streak >= CONFIRM_TICKS) {
				reported.add(id);
				misses.remove(id);
				emit(id, player.blockPosition(), "player entity is in the world but not in the tab list");
			}
		}
	}
}
