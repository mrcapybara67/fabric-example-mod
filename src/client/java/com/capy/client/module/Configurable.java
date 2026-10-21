package com.capy.client.module;

/**
 * Implemented by modules that keep state between game sessions.
 *
 * The client configuration writes one JSON object per module, so a module
 * only has to describe how its own values are read and written.
 */
public interface Configurable {
	/**
	 * Restores the module's values from the stored JSON.
	 *
	 * Implementations must tolerate missing, wrongly typed or out-of-range
	 * values and fall back to their defaults instead of throwing.
	 */
	void loadConfig(com.google.gson.JsonObject json);

	/** Writes the module's values into the given JSON object. */
	void saveConfig(com.google.gson.JsonObject json);
}
