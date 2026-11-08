package com.capy.client.module.modules.antiadmin;

/** The stable, ordered vocabulary the staff list shows for one name. */
public enum PresenceStatus {
	VANISHED,
	NEARBY,
	ONLINE,
	SPECTATOR,
	OFFLINE,
	UNKNOWN;

	/** Row order: vanished first, then nearby, online, spectator, offline. */
	public int rowOrder() {
		return switch (this) {
			case VANISHED -> 0;
			case NEARBY -> 1;
			case ONLINE -> 2;
			case SPECTATOR -> 3;
			case OFFLINE -> 4;
			case UNKNOWN -> 5;
		};
	}
}
