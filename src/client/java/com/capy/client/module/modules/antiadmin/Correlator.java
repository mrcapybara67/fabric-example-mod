package com.capy.client.module.modules.antiadmin;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

/**
 * Reads the signal buffer once per tick and decides what it adds up to.
 *
 * The scoring is deliberately explainable — a user looking at a card can see
 * why it fired:
 *
 * <pre>
 *   CERTAIN  a probe hit, or an unlisted entity plus any second kind
 *   HIGH     any strong kind, or two independent kinds
 *   MEDIUM   one weak kind that reached its threshold
 *   LOW      one weak kind below its threshold (only shown when asked for)
 * </pre>
 *
 * "Independent" means distinct {@link DetectionKind}s, not distinct
 * observations: ten orphan particles are one kind of evidence, a particle and
 * a footstep are two. {@link DetectionKind#NPC_FINGERPRINT} never counts —
 * it exists to rule a subject out, not to accuse it.
 *
 * A subject an {@code NpcFingerprinter} has identified is skipped entirely, so
 * a static stand-in is never reported as a vanished staff member.
 */
public final class Correlator {
	private final SignalBuffer buffer;
	private final AlertManager alerts;
	private final KnownPlayerStore known;
	private final Set<UUID> npcs;

	public Correlator(SignalBuffer buffer, AlertManager alerts, KnownPlayerStore known, Set<UUID> npcs) {
		this.buffer = buffer;
		this.alerts = alerts;
		this.known = known;
		this.npcs = npcs;
	}

	/**
	 * Runs the correlation for this tick.
	 *
	 * @param minimum the lowest confidence the user wants to see
	 * @return how many subjects produced an alert this tick
	 */
	public int tick(Minecraft mc, ServerProfile profile, Confidence minimum, String host) {
		if (mc == null || mc.level == null || mc.player == null || profile == null) {
			return 0;
		}
		int raised = 0;
		for (Map.Entry<UUID, List<Observation>> entry : buffer.snapshot().entrySet()) {
			AlertRecord record = evaluate(mc, entry.getKey(), entry.getValue(), profile, minimum, host);
			if (record == null || !alerts.raise(record)) {
				continue;
			}
			// Only a record that actually reached the user counts as a detection,
			// so the stored history is a count of alerts, not of frames.
			known.note(record.uuid(), record.name(), record.position(),
					record.kinds().isEmpty() ? null : record.kinds().get(0), record.timestamp());
			raised++;
		}
		return raised;
	}

	/** Scores one subject, or returns null when it is not worth an alert. */
	private AlertRecord evaluate(Minecraft mc, UUID subject, List<Observation> observations,
			ServerProfile profile, Confidence minimum, String host) {
		if (observations.isEmpty() || npcs.contains(subject)) {
			return null;
		}
		Map<DetectionKind, Integer> counts = new EnumMap<>(DetectionKind.class);
		List<Observation> counted = new ArrayList<>(observations.size());
		for (Observation observation : observations) {
			if (observation.kind() == DetectionKind.NPC_FINGERPRINT) {
				continue;
			}
			if (profile.isDisabled(observation.kind())) {
				continue;
			}
			counts.merge(observation.kind(), 1, Integer::sum);
			counted.add(observation);
		}
		if (counts.isEmpty()) {
			return null;
		}

		List<DetectionKind> confirmed = new ArrayList<>(counts.size());
		for (Map.Entry<DetectionKind, Integer> entry : counts.entrySet()) {
			if (entry.getValue() >= profile.threshold(entry.getKey())) {
				confirmed.add(entry.getKey());
			}
		}

		Confidence confidence = score(counts, confirmed);
		BlockPos position = lastPosition(counted);
		Confidence floor = profile.regionFloor(position);
		if (floor != null) {
			confidence = confidence.atLeastOf(floor);
		}
		if (!confidence.atLeast(minimum)) {
			return null;
		}

		return new AlertRecord(subject, nameOf(mc, subject), confidence, counted, System.currentTimeMillis(),
				position, distanceTo(mc.player, position), host, kindFor(counted));
	}

	/** The confidence ladder described on the class. */
	private static Confidence score(Map<DetectionKind, Integer> counts, List<DetectionKind> confirmed) {
		if (counts.containsKey(DetectionKind.PROBE_CONFIRMED)) {
			return Confidence.CERTAIN;
		}
		if (counts.containsKey(DetectionKind.ENTITY_NOT_IN_TAB) && confirmed.size() >= 2) {
			return Confidence.CERTAIN;
		}
		if (confirmed.size() >= 2) {
			return Confidence.HIGH;
		}
		if (confirmed.size() == 1) {
			return confirmed.get(0).strong() ? Confidence.HIGH : Confidence.MEDIUM;
		}
		return Confidence.LOW;
	}

	/** The wording the face alert uses, picked by the strongest thing proven. */
	private static AdminAlert.Kind kindFor(List<Observation> observations) {
		boolean ghost = false;
		boolean vanished = false;
		boolean hiddenTag = false;
		boolean spectator = false;
		for (Observation observation : observations) {
			switch (observation.kind()) {
				case PROBE_CONFIRMED -> {
					return AdminAlert.Kind.PROBED;
				}
				case ENTITY_NOT_IN_TAB -> ghost = true;
				case SILENT_TAB_REMOVE, SHADOW_MAP_DELTA, PHANTOM_PING, RAPID_PACKET_CLUSTER, VANISH_TEXT ->
						vanished = true;
				case SILENT_TEAM_REMOVE -> hiddenTag = true;
				case SPECTATOR_CYCLE -> spectator = true;
				default -> {
				}
			}
		}
		if (ghost) {
			return AdminAlert.Kind.GHOST;
		}
		if (vanished) {
			return AdminAlert.Kind.VANISHED;
		}
		if (hiddenTag) {
			return AdminAlert.Kind.HIDDEN_TAG;
		}
		if (spectator) {
			return AdminAlert.Kind.SPECTATOR;
		}
		return AdminAlert.Kind.UNSEEN;
	}

	/** The newest observation that carries a position. */
	private static BlockPos lastPosition(List<Observation> observations) {
		for (int i = observations.size() - 1; i >= 0; i--) {
			BlockPos position = observations.get(i).position();
			if (position != null) {
				return position;
			}
		}
		return null;
	}

	/** The account name, the remembered name, or a stand-in for a cell. */
	private static String nameOf(Minecraft mc, UUID subject) {
		ClientPacketListener connection = mc.getConnection();
		PlayerInfo info = connection == null ? null : connection.getPlayerInfo(subject);
		if (info != null && info.getProfile() != null && info.getProfile().name() != null
				&& !info.getProfile().name().isEmpty()) {
			return info.getProfile().name();
		}
		// A probed account has no profile left; the detector that confirmed it
		// kept the name it was confirmed under.
		String remembered = SignalBuffer.subjectName(subject);
		if (remembered != null) {
			return remembered;
		}
		return SignalBuffer.isSpatial(subject) ? "someone" : "unknown";
	}

	/** Blocks to the last known place, or -1 when there is no place. */
	private static double distanceTo(Player player, BlockPos position) {
		if (player == null || position == null) {
			return -1.0;
		}
		return Math.sqrt(player.distanceToSqr(position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5));
	}
}
