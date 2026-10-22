package com.capy.client.module.modules.antiadmin;

import com.capy.client.module.WatchlistSetting;
import java.util.ArrayList;
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
import net.minecraft.world.level.GameType;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;

/**
 * Finds the watched players the client can still see, and how.
 *
 * Only an <em>entity</em> carries a position. When the server sent one, the
 * distance is real and the radius means something; nothing else in this class
 * ever claims to know how far away a player is.
 *
 * Why a player list entry is not evidence of proximity
 * ---------------------------------------------------
 * An earlier build reported a watched name that merely appeared in the player
 * list as "ONLINE" and raised a proximity alert for it. That was wrong: on a
 * normal server every connected player is in that list, so it fired for a
 * staff member on the other side of the map, in another world, or in a lobby.
 * The list is never used to measure distance.
 *
 * What the list and the packets <em>can</em> prove is different, and is what
 * the rest of the module uses: that an account is connected (a join), that it
 * stopped being listed (a vanish), that its entity outlived its entry (a
 * ghost), or that the tab itself reports a spectator or a nametag-hiding team
 * ({@link #scanTells}). Those are states, not distances, and each is
 * announced as such. The packet level signals live in {@code PacketSignals}.
 *
 * What this class deliberately does <em>not</em> do:
 * <ul>
 *   <li>infer nearby presence from a name alone;</li>
 *   <li>claim that a matched player is an administrator - the watchlist says
 *       "watch this account", nothing more;</li>
 *   <li>detect a player the server never sends. A vanish system that removes
 *       the account from the client leaves nothing behind, so nothing on the
 *       client can see them; a player in spectator mode is not sent to other
 *       clients either. Missing information is treated as unknown, never as
 *       evidence that nobody is around.</li>
 * </ul>
 */
public final class AdminDetector {
	/**
	 * An observed watched player.
	 *
	 * @param distance how far away the client last saw them, or -1 when it
	 *                 never had a position
	 * @param ageMs    how long ago that position was read, 0 for a live one
	 */
	public record Match(UUID uuid, String username, String identity, double distance, long ageMs,
			PlayerSkin skin) {
	}

	/** What the tab list itself gives away about a listed player. */
	public enum Tell {
		/** In spectator mode: invisible to survival players, but still listed. */
		SPECTATOR,
		/** On a team that hides nametags: the older vanish style. */
		HIDDEN_TAG
	}

	/** A tell, attached to the player it belongs to. */
	public record TabTell(Match match, Tell tell) {
	}

	private AdminDetector() {
	}

	/**
	 * Scans the client for watched players that are in the world.
	 *
	 * The radius is applied by the caller rather than here, because the
	 * positions are useful beyond it: a vanish that removes the entity can
	 * still be reported with where the player was standing when it happened.
	 *
	 * @return every watched player the server sent, closest first
	 */
	public static List<Match> scan(Minecraft mc, WatchlistSetting watchlist) {
		List<Match> out = new ArrayList<>();
		if (mc == null || watchlist == null) {
			return out;
		}
		ClientLevel level = mc.level;
		LocalPlayer local = mc.player;
		if (level == null || local == null) {
			return out;
		}

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
			out.add(new Match(player.getUUID(),
					profileName == null || profileName.isEmpty() ? identity : profileName,
					identity,
					Math.sqrt(player.distanceToSqr(local)),
					0L,
					player.getSkin()));
		}

		out.sort((a, b) -> Double.compare(a.distance(), b.distance()));
		return out;
	}

	/**
	 * Collects watched players that have just appeared in the player list.
	 *
	 * Every listed player is added to {@code seen} the first time it is seen,
	 * so a single arrival is reported exactly once. That is also what keeps a
	 * server that rebuilds its tab list - or a player who moves between worlds
	 * and briefly drops out - from re-reporting the same person: once a UUID
	 * is in the set it is never reported again for the rest of the session.
	 *
	 * This is a join announcement, not evidence of proximity. The distance is
	 * therefore unknown and reported as such.
	 *
	 * @param seen UUIDs already listed this session; new arrivals are added
	 * @return the watched arrivals, in player list order
	 */
	public static List<Match> scanJoins(Minecraft mc, WatchlistSetting watchlist, Set<UUID> seen) {
		List<Match> out = new ArrayList<>();
		if (mc == null || watchlist == null || seen == null || mc.getConnection() == null) {
			return out;
		}
		for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
			if (info == null || info.getProfile() == null || info.getProfile().id() == null) {
				continue;
			}
			UUID id = info.getProfile().id();
			if (!seen.add(id)) {
				continue;
			}
			String identity = matchIdentity(info, watchlist);
			if (identity == null) {
				continue;
			}
			out.add(toMatch(info, identity));
		}
		return out;
	}

	/**
	 * Reads the two tells that a listed tab entry carries.
	 *
	 * A spectator is not sent to survival clients at all, so no scan of the
	 * world can find one - but the tab list still reports the game mode. A team
	 * with a restricted nametag visibility is how the older vanish plugins hide
	 * a player while leaving them listed.
	 *
	 * Only listed entries are considered: an unlisted one is already reported
	 * as a vanish, and reporting it twice would tell the player nothing new.
	 */
	public static List<TabTell> scanTells(Minecraft mc, WatchlistSetting watchlist) {
		List<TabTell> out = new ArrayList<>();
		if (mc == null || watchlist == null || mc.getConnection() == null || mc.level == null) {
			return out;
		}
		for (PlayerInfo info : mc.getConnection().getListedOnlinePlayers()) {
			String identity = matchIdentity(info, watchlist);
			if (identity == null) {
				continue;
			}
			if (info.getGameMode() == GameType.SPECTATOR) {
				out.add(new TabTell(toMatch(info, identity), Tell.SPECTATOR));
				continue;
			}
			PlayerTeam team = info.getTeam();
			if (team != null && team.getNameTagVisibility() != Team.Visibility.ALWAYS) {
				out.add(new TabTell(toMatch(info, identity), Tell.HIDDEN_TAG));
			}
		}
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
		return matchIdentity(info, watchlist);
	}

	/**
	 * Returns the identity of a player list entry that matched the watchlist,
	 * or null when the entry is not watched.
	 */
	private static String matchIdentity(PlayerInfo info, WatchlistSetting watchlist) {
		if (info == null || info.getProfile() == null) {
			return null;
		}
		String infoName = info.getProfile().name();
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

	/** Builds a match from a tab entry, so the same fallbacks are used everywhere. */
	private static Match toMatch(PlayerInfo info, String identity) {
		String name = info.getProfile().name();
		return new Match(info.getProfile().id(),
				name == null || name.isEmpty() ? identity : name,
				identity,
				-1.0,
				0L,
				info.getSkin());
	}

	private static PlayerInfo playerInfo(Minecraft mc, AbstractClientPlayer player) {
		if (mc.getConnection() == null) {
			return null;
		}
		return mc.getConnection().getPlayerInfo(player.getUUID());
	}
}
