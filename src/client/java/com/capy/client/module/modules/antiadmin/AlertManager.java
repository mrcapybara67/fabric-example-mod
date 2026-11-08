package com.capy.client.module.modules.antiadmin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Turns correlated detections into exactly one alert at a time.
 *
 * The rules:
 * <ul>
 *   <li>a card sits in the HUD for {@link #CARD_MS}, so a detection stays
 *       readable after the face alert has played;</li>
 *   <li>the same account saying the same thing again while its state is
 *       unchanged is dropped — a vanished player who keeps setting off the
 *       same detector must not restart the alert every tick;</li>
 *   <li>a higher confidence reopens the alert immediately, because the second
 *       piece of evidence is the one worth looking up for;</li>
 *   <li>a different <em>kind</em> is always new information. A sighting
 *       followed by a vanish is not a repeat, it is the sequel.</li>
 * </ul>
 *
 * The manager owns no rendering. It exposes the alert that is being shown and
 * hands every accepted record to the sink the module installed, which is where
 * the sound and the webhook live.
 */
public final class AlertManager {
	/** How long an alert stays on the HUD. */
	public static final long CARD_MS = 30_000L;

	private final Map<UUID, Raised> raised = new HashMap<>();
	private Consumer<AlertRecord> sink = record -> {
	};
	private AdminAlert active;
	private long activeAt;
	private AlertRecord activeRecord;

	/** Drops every trace of the previous session. */
	public void reset() {
		raised.clear();
		active = null;
		activeRecord = null;
		activeAt = 0L;
	}

	/** Installs the side effects (sound, webhook). Called once by the module. */
	public void sink(Consumer<AlertRecord> sink) {
		this.sink = sink == null ? record -> {
		} : sink;
	}

	/**
	 * Offers a record to the manager.
	 *
	 * @return true when it was accepted and is now the active alert
	 */
	public boolean raise(AlertRecord record) {
		if (record == null) {
			return false;
		}
		if (AlertGate.suppressedByLogout(record.name())) {
			// A normal logout is not a vanish. The leave line may arrive just
			// before or just after the removal packets, so this checks both
			// directions inside one four-second window.
			return false;
		}
		long now = System.currentTimeMillis();
		Raised previous = raised.get(record.uuid());
		if (previous != null) {
			// A state change is a different kind or a higher confidence. The
			// correlator re-evaluates every subject every tick, so repeating the
			// same state would restart the face and keep the wording from ever
			// appearing. It stays quiet until the state is cleared or changes.
			boolean stronger = record.confidence().ordinal() > previous.confidence.ordinal();
			if (previous.kind == record.kind() && !stronger) {
				return false;
			}
		}
		raised.put(record.uuid(), new Raised(record.name(), record.kind(), record.confidence()));
		active = toAlert(record, now);
		activeAt = now;
		activeRecord = record;
		sink.accept(record);
		return true;
	}

	/**
	 * Drops an alert for a name that has left normally.
	 *
	 * <p>Called from the tick so a leave line that arrives after the removal
	 * packets still clears the card, not only the next alert.</p>
	 */
	public void cancelGone() {
		raised.entrySet().removeIf(entry -> {
			Raised state = entry.getValue();
			if (AlertGate.suppressedByLogout(state.name)) {
				if (activeRecord != null && activeRecord.uuid().equals(entry.getKey())) {
					active = null;
					activeRecord = null;
				}
				return true;
			}
			return false;
		});
	}

	/** Clears the state for one account, e.g. after it is listed again. */
	public void clearSubject(UUID subject) {
		if (subject == null) {
			return;
		}
		raised.remove(subject);
		if (activeRecord != null && subject.equals(activeRecord.uuid())) {
			active = null;
			activeRecord = null;
		}
	}

	/** Clears a state by name, for a probe that proved the name absent. */
	public void clearName(String name) {
		if (name == null) {
			return;
		}
		raised.entrySet().removeIf(entry -> {
			boolean match = name.equalsIgnoreCase(entry.getValue().name);
			if (match && activeRecord != null && activeRecord.uuid().equals(entry.getKey())) {
				active = null;
				activeRecord = null;
			}
			return match;
		});
	}

	/** The alert being shown, or null once it has played out. */
	public AdminAlert active() {
		if (active == null || active.finished(System.currentTimeMillis())) {
			active = null;
			return null;
		}
		return active;
	}

	/** The record behind the alert being shown, for the HUD cards. */
	public AlertRecord activeRecord() {
		return active == null ? null : activeRecord;
	}

	/** When the active alert started, in wall clock milliseconds. */
	public long activeAt() {
		return activeAt;
	}

	public boolean busy(long now) {
		return active != null && now - activeAt < CARD_MS;
	}

	public void clear() {
		active = null;
		activeRecord = null;
	}

	/**
	 * Adapts a record to the display type the face renderer already draws.
	 *
	 * The skin is read here rather than in the correlator because the display
	 * is the only thing that needs it, and a missing entry (a vanished player
	 * whose profile is gone) simply falls back to the silhouette.
	 */
	private static AdminAlert toAlert(AlertRecord record, long now) {
		PlayerSkin skin = null;
		Minecraft mc = Minecraft.getInstance();
		if (mc != null) {
			ClientPacketListener connection = mc.getConnection();
			PlayerInfo info = connection == null ? null : connection.getPlayerInfo(record.uuid());
			if (info != null) {
				skin = info.getSkin();
			}
		}
		AdminDetector.Match match = new AdminDetector.Match(record.uuid(), record.name(), record.name(),
				record.distance(), 0L, skin);
		return new AdminAlert(match, record.kind(), now);
	}

	private record Raised(String name, AdminAlert.Kind kind, Confidence confidence) {
	}
}
