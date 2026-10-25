package com.capy.client.module.modules.antiadmin;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

/**
 * Decides which profile is in force right now.
 *
 * Two ways in: the user pinned a profile in the settings, or the matcher reads
 * the host the client is connected to. A pinned profile wins, because someone
 * who selected "anarchy" on a server the built-ins do not know about is making
 * a deliberate choice, not asking for a guess.
 *
 * The matcher is cheap on purpose: it is consulted once per join and when the
 * selector changes, never per tick, and the result is cached by the caller.
 */
public final class ServerProfileMatcher {
	private final List<ServerProfile> custom = new ArrayList<>();

	/**
	 * Resolves the profile for the server the client is on.
	 *
	 * @param selected a profile id, or {@code auto}
	 * @param host     the host the current session is using, or null
	 */
	public ServerProfile resolve(String selected, String host) {
		if (selected != null && !ServerProfile.AUTO.equals(selected)) {
			ServerProfile pinned = ServerProfile.byId(selected);
			if (pinned != null) {
				return pinned;
			}
		}
		return match(host);
	}

	/** Resolves the profile for the current connection. */
	public ServerProfile resolve(Minecraft mc, String selected) {
		return resolve(selected, hostOf(mc));
	}

	/**
	 * The best matching profile for a host.
	 *
	 * Custom profiles are consulted first so a user entry can override a
	 * built-in on the same host, then the specific built-ins, then the default.
	 */
	public ServerProfile match(String hostname) {
		if (hostname == null || hostname.isBlank()) {
			return ServerProfile.DEFAULT;
		}
		for (ServerProfile profile : custom) {
			if (profile.matchesHost(hostname)) {
				return profile;
			}
		}
		for (ServerProfile profile : ServerProfile.builtins()) {
			if (profile.matchesHost(hostname)) {
				return profile;
			}
		}
		return ServerProfile.DEFAULT;
	}

	/** Registers a custom profile ahead of the built-ins. */
	public void add(ServerProfile profile) {
		if (profile != null) {
			custom.add(0, profile);
		}
	}

	public List<ServerProfile> all() {
		List<ServerProfile> out = new ArrayList<>(custom);
		out.addAll(ServerProfile.builtins());
		return List.copyOf(out);
	}

	/** The host of the current session, without the port, or null. */
	public static String hostOf(Minecraft mc) {
		if (mc == null || mc.getConnection() == null) {
			return null;
		}
		ServerData data = mc.getConnection().getServerData();
		if (data == null || data.ip == null || data.ip.isBlank()) {
			return null;
		}
		return KnownPlayerStore.sanitizeHost(data.ip);
	}
}
