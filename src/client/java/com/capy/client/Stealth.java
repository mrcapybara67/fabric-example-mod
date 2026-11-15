package com.capy.client;

import com.capy.client.config.ClientConfig;
import net.minecraft.client.ClientBrandRetriever;

/** One switch for every client-identity leak the server can observe. */
public final class Stealth {
	private Stealth() {
	}

	public static boolean enabled() {
		ClientConfig config = ClientConfig.get();
		return config == null || config.stealth();
	}

	/** What the handshake should call us while stealth is on. */
	public static String brand(String normal) {
		return enabled() ? ClientBrandRetriever.VANILLA_NAME : normal;
	}
}
