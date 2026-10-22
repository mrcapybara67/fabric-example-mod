package com.capy.client.module.modules.antiadmin;

import com.capy.client.module.WatchlistSetting;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Finds the watched players that are actually around the local player.
 *
 * A match is only reported when the server sent the player's <em>entity</em>
 * to the client and that entity is inside the detection radius. That is the
 * one case where the client genuinely knows the player is there and how far
 * away they are.
 *
 * Why the tab list is deliberately not used
 * -----------------------------------------
 * An earlier build also reported a watched name that merely appeared in the
 * player list as "ONLINE" and raised an alert for it. That is not evidence of
 * anything: on a normal server every connected player is in that list, so the
 * alert fired for a staff member who was on the other side of the map, in
 * another world, or in a lobby - nowhere near the player. The only thing the
 * list proves is that an account is connected to the same server, and the
 * detection radius cannot be measured from it at all. Those false positives
 * are gone: the list is no longer consulted when deciding whether to alert.
 *
 * What this class deliberately does <em>not</em> do:
 * <ul>
 *   <li>infer nearby presence from a name alone;</li>
 *   <li>claim that a matched player is an administrator - the watchlist says
 *       "watch this account", nothing more;</li>
 *   <li>detect a player the server never sends. A server side vanish system
 *       removes the player from the client entirely, so nothing on the client
 *       can see them; a player in spectator mode is not sent to other clients
 *       either. Missing information is treated as unknown, never as evidence
 *       that nobody is around.</li>
 * </ul>
 */
public final class AdminDetector {
	/** An observed watched player, with the distance the client measured. */
	public record Match(UUID uuid, String username, String identity, double distance, PlayerSkin skin) {
	}

	private AdminDetector() {
	}

	/**
	 * Scans the client for watched players that are in the world and in range.
	 *
	 * @param range detection radius in blocks
	 * @return every watched player inside the radius, closest first
	 */
	public static List<Match> scan(Minecraft mc, WatchlistSetting watchlist, double range) {
		List<Match> out = new ArrayList<>();
		if (mc == null || watchlist == null) {
			return out;
		}
		ClientLevel level = mc.level;
		LocalPlayer local = mc.player;
		if (level == null || local == null) {
			return out;
		}

		double rangeSq = range * range;

		// Players the server actually sent to us: their position is known, so
		// the distance is real and the radius means something.
		for (AbstractClientPlayer player : level.players()) {
			if (player == local || player.isRemoved()) {
				continue;
			}
			String profileName = player.getGameProfile() == null ? null : player.getGameProfile().name();
			String identity = matchIdentity(mc, player, profileName, watchlist);
			if (identity == null) {
				continue;
			}
			double distanceSq = player.distanceToSqr(local);
			if (distanceSq > rangeSq) {
				continue;
			}
			out.add(new Match(player.getUUID(),
					profileName == null || profileName.isEmpty() ? identity : profileName,
					identity,
					Math.sqrt(distanceSq),
					player.getSkin()));
		}

		out.sort((a, b) -> Double.compare(a.distance(), b.distance()));
		return out;
	}

	/**
	 * Returns the identity that matched the watchlist, or null when the
	 * player is not watched.
	 *
	 * The game profile name is the primary identity. The player list entry is
	 * consulted as well, because a server with a cross-platform bridge can
	 * expose a different, equally reliable display name for the same player.
	 * This only decides <em>who</em> a player is; it never decides whether
	 * they count as nearby.
	 */
	private static String matchIdentity(Minecraft mc, AbstractClientPlayer player, String profileName,
			WatchlistSetting watchlist) {
		if (profileName != null && !profileName.isEmpty() && watchlist.matches(profileName)) {
			return profileName;
		}
		PlayerInfo info = playerInfo(mc, player);
		if (info == null) {
			return null;
		}
		String infoName = info.getProfile() == null ? null : info.getProfile().name();
		if (infoName != null && !infoName.isEmpty() && watchlist.matches(infoName)) {
			return infoName;
		}
		Component display = info.getTabListDisplayName();
		if (display != null) {
			String text = display.getString().trim();
			if (!text.isEmpty() && watchlist.matches(text)) {
				return text;
			}
		}
		return null;
	}

	private static PlayerInfo playerInfo(Minecraft mc, AbstractClientPlayer player) {
		if (mc.getConnection() == null) {
			return null;
		}
		return mc.getConnection().getPlayerInfo(player.getUUID());
	}
}
