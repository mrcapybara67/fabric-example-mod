package com.capy.client.module.modules.antiadmin;

/** What the last completed probe cycle proved about a name. */
public enum ProbeState {
	/** No completed cycle yet. */
	UNKNOWN,
	/** The server completed the name while the tab list hid it. */
	HIDDEN,
	/** Every working command was tried and none returned the name. */
	ABSENT
}
