package com.capy.client.module.modules.antiadmin;

/**
 * How strong the evidence for a detection is.
 *
 * The alert never claims more than the client can actually observe:
 * <ul>
 *   <li>{@link #NEARBY} — the server sent the player's entity, so the client
 *       knows the player exists <em>and</em> how far away they are;</li>
 *   <li>{@link #ONLINE} — the server lists the player, but no entity is
 *       available, so the client knows the player is connected and nothing
 *       about their position.</li>
 * </ul>
 */
public enum Evidence {
	/** The player's entity is observable and inside the detection radius. */
	NEARBY("nearby"),
	/** The player is listed by the server but has no observable entity. */
	ONLINE("online");

	private final String id;

	Evidence(String id) {
		this.id = id;
	}

	public String id() {
		return id;
	}

	/** True when the client can measure a distance to the player. */
	public boolean hasDistance() {
		return this == NEARBY;
	}
}
