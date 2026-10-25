package com.capy.client.module.modules.antiadmin;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;

/**
 * Decides which player-shaped entities are actually server side props.
 *
 * Servers fill lobbies and shops with armour stands or fake players wearing a
 * player skin. They are always in the tab list, they never move, and their
 * reported latency never changes because it is a stored number, not a
 * measurement. A real connection has jitter of at least a millisecond or two
 * over a second and a half of sampling.
 *
 * Its finding is used to <em>suppress</em>, not to accuse: an account marked
 * here is skipped by the entity cross reference and by the correlator, so a
 * shopkeeper hologram cannot be reported as a vanished administrator. The
 * setting that turns it off therefore turns the protection off too.
 *
 * The kind is registered even though this detector never pushes an observation:
 * it is the name the ruling is made under, and it keeps the per-signal toggle
 * list complete.
 */
public final class NpcFingerprinter extends AbstractDetector {
	/** Samples needed before the ruling is made: 1.5 s at twenty ticks. */
	private static final int SAMPLES = 30;
	/** Latency range in milliseconds a real connection cannot stay under. */
	private static final int FLAT_PING_MS = 2;

	private final Set<UUID> npcs;
	private final Map<UUID, Track> tracks = new HashMap<>();
	/** Where each ruled-out account was standing when the ruling was made. */
	private final Map<UUID, BlockPos> watch = new HashMap<>();

	public NpcFingerprinter(SignalBuffer buffer, Set<UUID> npcs) {
		super(buffer);
		this.npcs = npcs;
	}

	@Override
	public String name() {
		return "npc_fingerprint";
	}

	@Override
	public DetectionKind kind() {
		return DetectionKind.NPC_FINGERPRINT;
	}

	@Override
	public void reset() {
		tracks.clear();
		watch.clear();
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!enabled() || mc.level == null || mc.player == null) {
			return;
		}
		for (AbstractClientPlayer player : mc.level.players()) {
			if (player == null || player == mc.player || player.isRemoved()) {
				continue;
			}
			UUID id = player.getUUID();
			BlockPos pos = player.blockPosition();
			if (npcs.contains(id)) {
				// A ruling is not permanent: an account that walks away from the
				// spot it was judged at is a person after all, and suppressing it
				// from then on would hide the one detection that matters.
				BlockPos judged = watch.get(id);
				if (judged == null) {
					watch.put(id, pos);
				} else if (judged.distSqr(pos) > 4.0) {
					npcs.remove(id);
					watch.remove(id);
				}
				continue;
			}
			PlayerInfo info = TabList.info(mc, id);
			if (info == null) {
				continue;
			}
			Track track = tracks.computeIfAbsent(id, key -> new Track(info.getLatency(), pos));
			if (!track.pos.equals(pos)) {
				track.pos = pos;
				track.moved = true;
			}
			track.min = Math.min(track.min, info.getLatency());
			track.max = Math.max(track.max, info.getLatency());
			track.samples++;
			if (track.samples < SAMPLES) {
				continue;
			}
			if (!track.moved && track.max - track.min < FLAT_PING_MS) {
				npcs.add(id);
				watch.put(id, pos);
			}
			tracks.remove(id);
		}
		tracks.keySet().removeIf(id -> mc.level.getPlayerByUUID(id) == null);
		watch.keySet().removeIf(id -> mc.level.getPlayerByUUID(id) == null);
	}

	/** True when the account has been ruled out as a server side stand-in. */
	public boolean isNpc(UUID uuid) {
		return uuid != null && npcs.contains(uuid);
	}

	private static final class Track {
		private int min;
		private int max;
		private int samples;
		private BlockPos pos;
		private boolean moved;

		private Track(int latency, BlockPos pos) {
			this.min = latency;
			this.max = latency;
			this.pos = pos;
		}
	}
}
