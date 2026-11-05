package com.capy.client.module.modules.antiadmin;

import com.capy.client.module.WatchlistSetting;
import com.capy.client.util.Guard;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
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
		HIDDEN_TAG,
		/**
		 * The connection still holds a player info entry for the account, but the
		 * tab list does not draw it. This is the shape a 1.21+ vanish takes: the
		 * listed flag is cleared and the entry is left behind.
		 */
		UNLISTED
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
	 * A watched account is added to {@code seen} the first time it is matched
	 * and then never reported again, which keeps a server that rebuilds its tab
	 * list - or a player who moves between worlds and briefly drops out - from
	 * re-reporting the same person all session.
	 *
	 * An account that is <em>not</em> watched is deliberately left out of
	 * {@code seen}. Remembering those was the reason a name typed into the
	 * watchlist did nothing until the server restarted: the account was already
	 * listed, so it had been marked as announced before anybody asked about it,
	 * and the join that mattered - the one after the name was added - was
	 * swallowed. Now the first scan after a name is typed announces the account
	 * once, and only once.
	 *
	 * This is a join announcement, not evidence of proximity. The distance is
	 * therefore unknown and reported as such.
	 *
	 * @param seen UUIDs already announced this session; new matches are added
	 * @return the watched arrivals, in player list order
	 */
	public static List<Match> scanJoins(Minecraft mc, WatchlistSetting watchlist, Set<UUID> seen) {
		List<Match> out = new ArrayList<>();
		if (mc == null || watchlist == null || seen == null || mc.getConnection() == null) {
			return out;
		}
		// Forget the accounts that are no longer listed. Without this, the first
		// arrival a watched account ever made was the only one ever reported:
		// its id stayed in `seen` for the rest of the session, so a player who
		// left and came back - which is exactly when a staff member turns up to
		// watch - was silently ignored. That is the bug behind "the guy I typed
		// in settings is on the server and it says nothing".
		Set<UUID> online = new HashSet<>();
		for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
			if (info != null && info.getProfile() != null && info.getProfile().id() != null) {
				online.add(info.getProfile().id());
			}
		}
		seen.retainAll(online);
		for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
			if (info == null || info.getProfile() == null || info.getProfile().id() == null) {
				continue;
			}
			UUID id = info.getProfile().id();
			if (seen.contains(id)) {
				continue;
			}
			String identity = matchIdentity(info, watchlist);
			if (identity == null) {
				continue;
			}
			// Remembered only now that it matched, so an account nobody was
			// watching is still unannounced when it is added to the list.
			seen.add(id);
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
	 * Every watched entry the connection still holds but the tab list does not
	 * draw.
	 *
	 * <p>This is the difference between the client's two player collections:
	 * {@code getOnlinePlayers()} is every entry the connection holds, and
	 * {@code getListedOnlinePlayers()} is the subset the tab list renders. A
	 * vanish that clears the listed flag leaves the entry, so the account is
	 * still nameable, still skinnable, and still evidence - it just is not shown.
	 * An account the server removed outright leaves nothing here; that case can
	 * only be reached by the probe.</p>
	 *
	 * <p>Transient mismatches while joining are possible, which is why the alert
	 * path still waits for the signal layer's five stable ticks. This method
	 * answers what the client holds <em>right now</em>; it is a read, not a
	 * detection.</p>
	 */
	public static List<TabTell> scanAllTabEntries(Minecraft mc, WatchlistSetting watchlist) {
		List<TabTell> out = new ArrayList<>();
		if (mc == null || watchlist == null || mc.getConnection() == null) {
			return out;
		}
		try {
			ClientPacketListener connection = mc.getConnection();
			Set<UUID> listed = new HashSet<>();
			for (PlayerInfo info : connection.getListedOnlinePlayers()) {
				if (info != null && info.getProfile() != null && info.getProfile().id() != null) {
					listed.add(info.getProfile().id());
				}
			}
			for (PlayerInfo info : connection.getOnlinePlayers()) {
				if (info == null || info.getProfile() == null || info.getProfile().id() == null) {
					continue;
				}
				if (listed.contains(info.getProfile().id())) {
					continue;
				}
				String identity = matchIdentity(info, watchlist);
				if (identity == null) {
					continue;
				}
				out.add(new TabTell(toMatch(info, identity), Tell.UNLISTED));
			}
		} catch (Throwable failure) {
			Guard.report("admin-scan-unlisted", failure);
		}
		return out;
	}

	/**
	 * Watched accounts the connection holds an entry for but that are in neither
	 * the world nor the drawn tab list.
	 *
	 * <p>An entry in the info map is proof the server told this client the
	 * account exists. Being absent from the world is normal on its own - another
	 * dimension, out of range, or a spectator - and being unlisted is normal
	 * during a join, so only the two together are reported, and only as
	 * "vanished", never as a distance.</p>
	 */
	public static List<Match> scanForVanished(Minecraft mc, WatchlistSetting watchlist) {
		List<Match> out = new ArrayList<>();
		if (mc == null || watchlist == null || mc.getConnection() == null || mc.level == null) {
			return out;
		}
		try {
			ClientPacketListener connection = mc.getConnection();
			Set<UUID> inWorld = new HashSet<>();
			for (AbstractClientPlayer player : mc.level.players()) {
				if (player != null) {
					inWorld.add(player.getUUID());
				}
			}
			Set<UUID> listed = new HashSet<>();
			for (PlayerInfo info : connection.getListedOnlinePlayers()) {
				if (info != null && info.getProfile() != null && info.getProfile().id() != null) {
					listed.add(info.getProfile().id());
				}
			}
			for (String name : watchlist.entries()) {
				PlayerInfo info = TabList.byNameIgnoreCase(mc, name);
				if (info == null || info.getProfile() == null || info.getProfile().id() == null) {
					continue;
				}
				UUID id = info.getProfile().id();
				if (inWorld.contains(id) || listed.contains(id)) {
					continue;
				}
				String identity = matchIdentity(info, watchlist);
				if (identity == null) {
					continue;
				}
				out.add(toMatch(info, identity));
			}
		} catch (Throwable failure) {
			Guard.report("admin-scan-vanished", failure);
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

	/**
	 * The freshest skin available for an account, or null.
	 *
	 * <p>The skin stored with an alert is a copy taken when the alert was
	 * raised. On a server where a player's skin is still being fetched at that
	 * moment - which is exactly what happens when somebody has just joined -
	 * that copy is the default skin, and the portrait then shows a face that
	 * belongs to nobody. Reading the skin again at draw time replaces it with
	 * the real texture the moment the client has it.
	 */
	public static PlayerSkin skinOf(Minecraft mc, UUID uuid) {
		if (mc == null || uuid == null) {
			return null;
		}
		if (mc.level != null) {
			try {
				net.minecraft.world.entity.player.Player player = mc.level.getPlayerByUUID(uuid);
				if (player instanceof AbstractClientPlayer client) {
					return client.getSkin();
				}
			} catch (RuntimeException | LinkageError ignored) {
				// Fall through to the player list below.
			}
		}
		if (mc.getConnection() != null) {
			try {
				PlayerInfo info = mc.getConnection().getPlayerInfo(uuid);
				if (info != null) {
					return info.getSkin();
				}
			} catch (RuntimeException | LinkageError ignored) {
				// No entry left to read.
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
