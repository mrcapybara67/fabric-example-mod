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
 *   <li>the same account saying the same thing again inside
 *       {@link #DEDUP_MS} is dropped — a vanished player who keeps setting off
 *       the same detector must not restart the alert every tick;</li>
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
	/** Repeat of the same account and kind inside this window is ignored. */
	public static final long DEDUP_MS = 30_000L;
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
		long now = System.currentTimeMillis();
		Raised previous = raised.get(record.uuid());
		if (previous != null && previous.kind == record.kind()
				&& now - previous.at < DEDUP_MS
				&& !record.confidence().atLeast(previous.confidence)) {
			return false;
		}
		raised.put(record.uuid(), new Raised(record.kind(), record.confidence(), now));
		active = toAlert(record, now);
		activeAt = now;
		activeRecord = record;
		sink.accept(record);
		return true;
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

	private record Raised(AdminAlert.Kind kind, Confidence confidence, long at) {
	}
}
