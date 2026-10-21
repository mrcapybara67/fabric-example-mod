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
 * Finds the watched players the client can actually observe.
 *
 * What counts as observable:
 *   <ul>
 *     <li>a player entity that the server sent to the client — its position,
 *         distance and skin are known;</li>
 *     <li>the identity the client knows for that player, taken from the game
 *         profile and from the player list entry (the tab list display name
 *         is what a cross-platform server exposes for a Bedrock player).</li>
 *   </ul>
 *
 * What does <em>not</em> count: a player the server never mentions. A
 * server-side vanish system usually removes the player from the client
 * entirely, so nothing here can (or claims to) detect it. Missing information
 * is treated as unknown, never as proof that no administrator is around.
 */
public final class AdminDetector {
	/** A watched player that is currently visible to the client. */
	public record Candidate(UUID uuid, String username, String identity, double distance, PlayerSkin skin) {
	}

	private AdminDetector() {
	}

	/**
	 * Scans the visible player entities for names on the watchlist.
	 *
	 * @param range detection radius in blocks
	 * @return every watched player inside the radius, nearest first
	 */
	public static List<Candidate> scan(Minecraft mc, WatchlistSetting watchlist, double range) {
		List<Candidate> out = new ArrayList<>();
		if (mc == null || watchlist == null) {
			return out;
		}
		ClientLevel level = mc.level;
		LocalPlayer local = mc.player;
		if (level == null || local == null) {
			return out;
		}
		List<AbstractClientPlayer> players = level.players();
		if (players.isEmpty()) {
			return out;
		}

		double rangeSq = range * range;
		for (AbstractClientPlayer player : players) {
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
			out.add(new Candidate(player.getUUID(),
					profileName == null || profileName.isEmpty() ? identity : profileName,
					identity,
					Math.sqrt(distanceSq),
					player.getSkin()));
		}
		out.sort((a, b) -> Double.compare(a.distance(), b.distance()));
		return out;
	}

	/**
	 * Returns the identity that matched the watchlist, or null when the player
	 * is not watched.
	 *
	 * The game profile name is the primary identity. The player list entry is
	 * consulted as well, because a server with a cross-platform bridge can
	 * expose a different, equally reliable display name for the same player.
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
