package com.capy.client.module.modules.antiadmin;

import com.capy.client.module.WatchlistSetting;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * Two levels of evidence are produced, and they are never mixed up:
 * <ul>
 *   <li><b>NEARBY</b> — the server sent the player's entity to the client, so
 *       the client knows the player is in the world and can measure the
 *       distance;</li>
 *   <li><b>ONLINE</b> — the server lists the player (the tab list), but no
 *       entity is available, so the client only knows that the player is
 *       connected. The position is unknown and is reported as unknown.</li>
 * </ul>
 *
 * What this class deliberately does <em>not</em> do:
 * <ul>
 *   <li>infer nearby presence from a name alone;</li>
 *   <li>claim that a matched player is an administrator — the watchlist says
 *       "watch this account", nothing more;</li>
 *   <li>detect a player the server never mentions. A server side vanish
 *       system usually removes the player from the client entirely, so no
 *       client side mod can see them. Missing information is treated as
 *       unknown, never as proof that nobody is around.</li>
 * </ul>
 */
public final class AdminDetector {
	/** An observed watched player. */
	public record Match(UUID uuid, String username, String identity, double distance, PlayerSkin skin,
			Evidence evidence) {
	}

	private AdminDetector() {
	}

	/**
	 * Scans the client for watched players.
	 *
	 * @param range detection radius in blocks
	 * @return every watched player the client can observe, strongest first
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

		Set<UUID> seen = new HashSet<>();
		double rangeSq = range * range;

		// 1. Players the server sent to us: their position is known.
		List<AbstractClientPlayer> players = level.players();
		for (AbstractClientPlayer player : players) {
			if (player == local || player.isRemoved()) {
				continue;
			}
			// Every loaded player counts as observed, even when they are out
			// of range: their position is known, so they are neither nearby
			// nor "online with an unknown position".
			seen.add(player.getUUID());
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
					player.getSkin(),
					Evidence.NEARBY));
		}

		// 2. Players the server lists but did not send an entity for.
		if (mc.getConnection() != null) {
			for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
				if (info == null || info.getProfile() == null) {
					continue;
				}
				if (seen.contains(info.getProfile().id()) || info.getProfile().id().equals(local.getUUID())) {
					continue;
				}
				String identity = matchIdentity(mc, info, watchlist);
				if (identity == null) {
					continue;
				}
				String name = info.getProfile() == null ? null : info.getProfile().name();
				out.add(new Match(info.getProfile().id(),
						name == null || name.isEmpty() ? identity : name,
						identity,
						-1.0,
						info.getSkin(),
						Evidence.ONLINE));
			}
		}

		out.sort((a, b) -> {
			if (a.evidence() != b.evidence()) {
				return a.evidence() == Evidence.NEARBY ? -1 : 1;
			}
			return Double.compare(a.distance(), b.distance());
		});
		return out;
	}

	/**
	 * Returns the identity that matched the watchlist, or null when the
	 * player is not watched.
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
		return matchIdentity(mc, info, watchlist);
	}

	private static String matchIdentity(Minecraft mc, PlayerInfo info, WatchlistSetting watchlist) {
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
