package com.capy.client.module.modules.antiadmin;

import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;

/**
 * One raised detection, ready to be shown and shipped.
 *
 * The record is the only thing the correlator hands on. It carries what the
 * alert has to say (who, how sure, why the client thinks so) and nothing about
 * how it will be drawn, so the HUD, the sound and the webhook all read the
 * same object instead of reconstructing it.
 *
 * {@code position} and {@code distance} are the last place the evidence was
 * seen, not a live position: by the time a vanished player is detected their
 * entity is usually gone, and reporting a stale place as current would be a
 * lie the whole feature is built to avoid.
 */
public record AlertRecord(UUID uuid, String name, Confidence confidence, List<Observation> signals,
		long timestamp, BlockPos position, double distance, String serverHost, AdminAlert.Kind kind) {

	public AlertRecord {
		signals = signals == null ? List.of() : List.copyOf(signals);
		name = name == null || name.isEmpty() ? "unknown" : name;
		confidence = confidence == null ? Confidence.LOW : confidence;
		kind = kind == null ? AdminAlert.Kind.UNSEEN : kind;
		serverHost = serverHost == null ? "unknown" : serverHost;
	}

	/** True when the client has a position to report. */
	public boolean hasPosition() {
		return position != null;
	}

	/** The distinct signal kinds, in the order they were seen. */
	public List<DetectionKind> kinds() {
		List<DetectionKind> out = new java.util.ArrayList<>(2);
		for (Observation observation : signals) {
			if (!out.contains(observation.kind())) {
				out.add(observation.kind());
			}
		}
		return out;
	}

	/** A comma separated tag line for the compact views. */
	public String tagLine() {
		StringBuilder text = new StringBuilder();
		for (DetectionKind kind : kinds()) {
			if (text.length() > 0) {
				text.append(" · ");
			}
			text.append(kind.tag());
		}
		// A proximity sighting is real evidence with no signal behind it, so
		// the card shows what happened rather than "nothing".
		return text.length() == 0 ? kind.name().replace('_', ' ') : text.toString();
	}

	/** Position as {@code x y z}, or an em dash when there is none. */
	public String positionText() {
		if (position == null) {
			return "—";
		}
		return position.getX() + " " + position.getY() + " " + position.getZ();
	}

	/** True when the evidence was seen within the last {@code ticks}. */
	public boolean fresh(long tick, int ticks) {
		for (Observation observation : signals) {
			if (observation.within(tick, ticks)) {
				return true;
			}
		}
		return false;
	}
}
