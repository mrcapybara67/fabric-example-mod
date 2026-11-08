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
 *   CERTAIN  a probe hit
 *   HIGH     two independent signal families
 *   MEDIUM   one family, including a strong tab-hidden signal
 *   LOW      nothing confirmed
 * </pre>
 *
 * The unit is the {@link SignalFamily}, not the individual observation: ten
 * tab-removal packets are one cause, while a tab removal plus a probe hit is
 * genuinely two. {@link DetectionKind#NPC_FINGERPRINT} never counts — it
 * exists to rule a subject out, not to accuse it.
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
	 * @param minimum  the lowest confidence the user wants to see
	 * @param watchlist when not null, only an account on it may produce an
	 *                  alert; null keeps the signal layer open to any named
	 *                  account
	 * @return how many subjects produced an alert this tick
	 */
	public int tick(Minecraft mc, ServerProfile profile, Confidence minimum, String host,
			com.capy.client.module.WatchlistSetting watchlist) {
		if (mc == null || mc.level == null || mc.player == null || profile == null) {
			return 0;
		}
		alerts.cancelGone();
		Map<UUID, List<Observation>> snapshot = buffer.snapshot();
		// A listed account is visible again: its vanished state is disproved,
		// so the next vanish can alert as a fresh state change.
		for (Map.Entry<UUID, List<Observation>> entry : snapshot.entrySet()) {
			if (TabList.listed(mc, entry.getKey())) {
				alerts.clearSubject(entry.getKey());
			}
		}
		int raised = 0;
		for (Map.Entry<UUID, List<Observation>> entry : snapshot.entrySet()) {
			AlertRecord record = evaluate(mc, entry.getKey(), entry.getValue(), profile, minimum, host, watchlist);
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
			ServerProfile profile, Confidence minimum, String host,
			com.capy.client.module.WatchlistSetting watchlist) {
		// A positional subject is a cell, not an account: two particles near
		// each other with no name attached. Those used to be reported as
		// "someone is near and unseen", which is what made the module shout at
		// a player standing alone - the cell is made by the signal itself, so
		// there is no account behind it to be right or wrong about. Only an
		// account can be reported; an unexplained particle is not a person.
		if (observations.isEmpty() || subject == null || npcs.contains(subject)
				|| SignalBuffer.isSpatial(subject)) {
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

		Confidence confidence = ConfidenceScoring.score(counts, confirmed);
		// Raise only when the evidence comes from more than one independent
		// family, or from the probe. A burst of tab-removal packets is one
		// family whether it is one packet or ten, and on its own it is also
		// what a normal logout looks like.
		long families = confirmed.stream()
				.map(DetectionKind::family)
				.filter(family -> family != SignalFamily.NEUTRAL)
				.distinct()
				.count();
		boolean probe = confirmed.contains(DetectionKind.PROBE_CONFIRMED);
		if (!probe && families < 2) {
			return null;
		}
		BlockPos position = lastPosition(counted);
		Confidence floor = profile.regionFloor(position);
		if (floor != null) {
			confidence = confidence.atLeastOf(floor);
		}
		if (!confidence.atLeast(minimum)) {
			return null;
		}

		// Who this is about, before it is reported. Evidence that names nobody is
		// not evidence about a person: the buffer can hold a subject whose
		// profile has already been removed, and the old code reported it as
		// "unknown" - which the player read as "the module is detecting someone
		// who is not even on my list". An alert that cannot say who it is about
		// is never shown.
		String name = nameOf(mc, subject);
		if (name == null || name.isEmpty() || "unknown".equals(name) || "someone".equals(name)) {
			return null;
		}
		// A leave line within the four-second logout window cancels the alert.
		// The check is on the resolved name so a burst of removal packets alone
		// cannot turn an ordinary logout into a vanish. The same gate is used by
		// AlertManager, so a regression that drops the leave check fails the
		// logout tests instead of shipping.
		if (!AlertGate.mayAnnounceVanish(name, probe, (int) families)) {
			return null;
		}
		// Watchlist only: with the setting on, the signal layer is corroboration
		// for the watched accounts, not a second, unnamed detector.
		if (watchlist != null && !watchlist.matches(name)) {
			return null;
		}
		return new AlertRecord(subject, name, confidence, counted, System.currentTimeMillis(),
				position, distanceTo(mc.player, position), host, kindFor(counted));
	}

	/**
	 * The wording the face alert uses, picked by the strongest thing proven.
	 *
	 * <p>Public because the staff list labels its rows with the same evidence:
	 * a probe hit, a silent tab removal or an unlisted entity has to read the
	 * same way in the box as it does in an alert, and two copies of this
	 * precedence would drift the first time a kind was added.</p>
	 */
	public static AdminAlert.Kind kindFor(List<Observation> observations) {
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
