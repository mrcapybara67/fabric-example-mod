package com.capy.client.module.modules.antiadmin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;

/**
 * The tab list, reduced to the questions the detectors actually ask.
 *
 * Vanilla exposes two collections and the difference between them is the whole
 * point of this module: {@code getOnlinePlayers} is every entry the client
 * holds, {@code getListedOnlinePlayers} is the subset the tab list draws. A
 * server that hides a player removes them from the second and leaves the
 * first alone, so the gap between the two is the detection.
 *
 * Every method tolerates a missing world or connection and answers "nothing
 * known" rather than throwing: a detector runs on the tick, and one that
 * explodes while the client is disconnecting would take the game down for a
 * signal that no longer exists.
 */
public final class TabList {
	private TabList() {
	}

	/** Every UUID the tab list is currently drawing. */
	public static Set<UUID> listed(Minecraft mc) {
		Set<UUID> out = new HashSet<>();
		ClientPacketListener connection = mc == null ? null : mc.getConnection();
		if (connection == null) {
			return out;
		}
		for (PlayerInfo info : connection.getListedOnlinePlayers()) {
			if (info != null && info.getProfile() != null && info.getProfile().id() != null) {
				out.add(info.getProfile().id());
			}
		}
		return out;
	}

	/** True when the entry exists at all, listed or not. */
	public static boolean online(Minecraft mc, UUID uuid) {
		return info(mc, uuid) != null;
	}

	/** True when the tab list is drawing this account. */
	public static boolean listed(Minecraft mc, UUID uuid) {
		if (uuid == null) {
			return false;
		}
		for (PlayerInfo info : listedInfos(mc)) {
			if (info.getProfile() != null && uuid.equals(info.getProfile().id())) {
				return true;
			}
		}
		return false;
	}

	/** The entry the client holds for this account, listed or not. */
	public static PlayerInfo info(Minecraft mc, UUID uuid) {
		ClientPacketListener connection = mc == null ? null : mc.getConnection();
		return connection == null || uuid == null ? null : connection.getPlayerInfo(uuid);
	}

	/** The entry for a name, or null. Exact case, like vanilla's own lookup. */
	public static PlayerInfo byName(Minecraft mc, String name) {
		ClientPacketListener connection = mc == null ? null : mc.getConnection();
		return connection == null || name == null ? null : connection.getPlayerInfo(name);
	}

	/**
	 * The entry for a name, ignoring case.
	 *
	 * Vanilla's lookup compares the strings directly, which is right for a name
	 * that came from the server and wrong for one read out of chat: a plugin may
	 * print a name in any case it likes.
	 */
	public static PlayerInfo byNameIgnoreCase(Minecraft mc, String name) {
		if (name == null) {
			return null;
		}
		PlayerInfo exact = byName(mc, name);
		if (exact != null) {
			return exact;
		}
		ClientPacketListener connection = mc == null ? null : mc.getConnection();
		if (connection == null) {
			return null;
		}
		for (PlayerInfo info : connection.getOnlinePlayers()) {
			if (info != null && info.getProfile() != null && name.equalsIgnoreCase(info.getProfile().name())) {
				return info;
			}
		}
		return null;
	}

	/** The account name, or null when the client knows nothing about it. */
	public static String nameOf(Minecraft mc, UUID uuid) {
		PlayerInfo info = info(mc, uuid);
		if (info == null || info.getProfile() == null) {
			return null;
		}
		String name = info.getProfile().name();
		return name == null || name.isEmpty() ? null : name;
	}

	private static Iterable<PlayerInfo> listedInfos(Minecraft mc) {
		ClientPacketListener connection = mc == null ? null : mc.getConnection();
		return connection == null ? Set.of() : connection.getListedOnlinePlayers();
	}
}
