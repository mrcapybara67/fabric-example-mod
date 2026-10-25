package com.capy.client.module.modules.antiadmin;

import java.util.Locale;

/**
 * Every signal the module can produce.
 *
 * Each constant carries its own base threshold and weight, so the correlator
 * and the server profiles never have to keep a second table in sync — a table
 * that, in the previous build, listed the same key twice and crashed the class
 * initialiser.
 *
 * {@code strong} is the trust in the signal, not its severity: a strong signal
 * is one that a vanilla server does not produce by accident, so a single
 * occurrence is already worth an alert. A weak signal needs repetition (the
 * threshold) or a second, independent kind before it is shown.
 */
public enum DetectionKind {
	/** An entity with a player UUID is in the world, but not in the tab list. */
	ENTITY_NOT_IN_TAB(1, true, "unlisted player entity"),
	/** The listed flag was cleared while the account stayed connected. */
	SILENT_TAB_REMOVE(1, true, "tab entry hidden"),
	/** A potion or witch particle with no player anywhere near it. */
	ORPHAN_PARTICLE(2, false, "particle with no player"),
	/** TNT or a crystal spawned with no player close enough to have placed it. */
	SOURCELESS_EXPLOSION(2, false, "explosion with no player"),
	/** Footsteps, armour, pickups or eating with no player to make the noise. */
	ORPHAN_SOUND(3, false, "sound with no player"),
	/** A door, button, lever or chest changed with no player close enough to use it. */
	HEADLESS_BLOCK_INTERACT(1, true, "block used with no player"),
	/** A chat message from an account that has no entity in the world. */
	HEADLESS_CHAT(1, true, "chat from no entity"),
	/** Chat wording that announces a vanish. */
	VANISH_TEXT(1, false, "vanish wording in chat"),
	/** The server's own tab completion named an account the tab list hides. */
	PROBE_CONFIRMED(1, true, "probe confirmed"),
	/** An unlisted account kept answering the server's latency checks. */
	PHANTOM_PING(1, false, "unlisted account still pinged"),
	/** Removed from a scoreboard team with no leave message behind it. */
	SILENT_TEAM_REMOVE(1, true, "removed from a team"),
	/** Switched to spectator while still listed and still connected. */
	SPECTATOR_CYCLE(1, true, "switched to spectator"),
	/** Several removal packets for one account inside a few milliseconds. */
	RAPID_PACKET_CLUSTER(1, true, "burst of removal packets"),
	/** A player info entry exists that the tab list does not draw. */
	SHADOW_MAP_DELTA(1, true, "hidden player info entry"),
	/** Flat ping and no movement: a server side stand-in, not a person. */
	NPC_FINGERPRINT(1, false, "static ping, no movement"),
	/** A sign editor or book opened without the local player doing anything. */
	GHOST_UI_OPEN(1, true, "UI opened with no interaction"),
	/** Slots pushed for a container the client never opened. */
	HEADLESS_CONTAINER_ACCESS(1, true, "container updated without opening");

	private final int threshold;
	private final boolean strong;
	private final String label;

	DetectionKind(int threshold, boolean strong, String label) {
		this.threshold = threshold;
		this.strong = strong;
		this.label = label;
	}

	/** How many observations inside the window confirm this kind. */
	public int threshold() {
		return threshold;
	}

	/** True when one occurrence is already good evidence. */
	public boolean strong() {
		return strong;
	}

	/** Readable evidence text, used by the alert cards and the webhook. */
	public String label() {
		return label;
	}

	/** The setting key the per-signal toggle is stored under. */
	public String settingKey() {
		return "signal_" + name().toLowerCase(Locale.ROOT);
	}

	/** The short tag shown on a card, e.g. {@code UNLISTED ENTITY}. */
	public String tag() {
		return name().replace('_', ' ');
	}

	/** Parses a stored name, returning null for anything unknown. */
	public static DetectionKind parse(String name) {
		if (name == null) {
			return null;
		}
		for (DetectionKind kind : values()) {
			if (kind.name().equalsIgnoreCase(name)) {
				return kind;
			}
		}
		return null;
	}
}
