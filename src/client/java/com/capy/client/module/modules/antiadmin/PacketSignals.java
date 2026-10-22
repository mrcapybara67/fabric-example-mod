package com.capy.client.module.modules.antiadmin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;

/**
 * The packet-level half of the detection.
 *
 * The tab list can be read on any tick, but a tick only samples it. These
 * three handlers see the exact packet that changed the state, so a vanish
 * cannot slip between two scans:
 *
 * <ul>
 *   <li>{@code UPDATE_LISTED false} - the account stays known, the tab list
 *       stops drawing it. That is a vanish;</li>
 *   <li>{@code PlayerInfoRemove} - the account is gone. Either they left or
 *       the server hid them harder; the caller decides with a ghost check;</li>
 *   <li>{@code CommandSuggestions} - the answer to a probe: the server's own
 *       list of online players, which includes vanished staff.</li>
 * </ul>
 *
 * Handlers run before vanilla applies the packet, so an entry that is about
 * to be removed can still be read for its name and skin.
 *
 * Both the recording and the draining happen on the client thread; packets
 * that are still on the network thread are ignored, because vanilla re-runs
 * the same handler on the client thread right after.
 */
public final class PacketSignals {
	/** What the packet said happened to a player. */
	public enum Kind {
		/** Still connected, no longer listed. */
		UNLISTED,
		/** The player info entry was removed outright. */
		REMOVED
	}

	/** One observed transition. */
	public record Event(Kind kind, UUID uuid, String name) {
	}

	/** The names the server offered for one completion request. */
	public record ProbeResponse(int id, List<String> names) {
	}

	/** Nothing may grow without bound if the module is off. */
	private static final int MAX = 64;
	/** Ids are kept far above vanilla's, so a reply is never misread. */
	private static final AtomicInteger PROBE_IDS = new AtomicInteger(Integer.MAX_VALUE - 512);

	private static final ArrayDeque<Event> events = new ArrayDeque<>();
	private static final ArrayDeque<ProbeResponse> responses = new ArrayDeque<>();

	private PacketSignals() {
	}

	// -------------------------------------------------------------- recording

	public static void onPlayerInfoUpdate(ClientboundPlayerInfoUpdatePacket packet) {
		if (!onClientThread()
				|| !packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED)) {
			return;
		}
		for (ClientboundPlayerInfoUpdatePacket.Entry entry : packet.entries()) {
			if (!entry.listed()) {
				push(new Event(Kind.UNLISTED, entry.profileId(), nameOf(entry.profile())));
			}
		}
	}

	public static void onPlayerInfoRemove(ClientboundPlayerInfoRemovePacket packet) {
		if (!onClientThread()) {
			return;
		}
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		for (UUID id : packet.profileIds()) {
			// Vanilla has not applied the removal yet, so the entry is readable.
			PlayerInfo info = connection == null ? null : connection.getPlayerInfo(id);
			push(new Event(Kind.REMOVED, id, info == null ? null : nameOf(info.getProfile())));
		}
	}

	public static void onSuggestions(ClientboundCommandSuggestionsPacket packet) {
		if (!onClientThread()) {
			return;
		}
		List<String> names = new ArrayList<>();
		for (var suggestion : packet.toSuggestions().getList()) {
			names.add(suggestion.getText());
		}
		pushResponse(new ProbeResponse(packet.id(), names));
	}

	// ----------------------------------------------------------------- probing

	/**
	 * Asks the server which players complete the name: the reply is the
	 * server's own online list, so it can name a player whose tab entry and
	 * entity were both removed.
	 *
	 * @return the request id the reply will carry, or -1 when there is no
	 *         connection to ask
	 */
	public static int probe(String name) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getConnection() == null) {
			return -1;
		}
		int id = PROBE_IDS.getAndIncrement();
		mc.getConnection().send(new ServerboundCommandSuggestionPacket(id, "msg " + name));
		return id;
	}

	// ----------------------------------------------------------------- draining

	public static List<Event> drainEvents() {
		if (events.isEmpty()) {
			return List.of();
		}
		List<Event> out = new ArrayList<>(events);
		events.clear();
		return out;
	}

	public static List<ProbeResponse> drainResponses() {
		if (responses.isEmpty()) {
			return List.of();
		}
		List<ProbeResponse> out = new ArrayList<>(responses);
		responses.clear();
		return out;
	}

	public static void clear() {
		events.clear();
		responses.clear();
	}

	// ---------------------------------------------------------------- internals

	private static boolean onClientThread() {
		Minecraft mc = Minecraft.getInstance();
		return mc != null && mc.isSameThread();
	}

	private static void push(Event event) {
		if (events.size() >= MAX) {
			events.pollFirst();
		}
		events.addLast(event);
	}

	private static void pushResponse(ProbeResponse response) {
		if (responses.size() >= MAX) {
			responses.pollFirst();
		}
		responses.addLast(response);
	}

	private static String nameOf(com.mojang.authlib.GameProfile profile) {
		return profile == null ? null : profile.name();
	}
}
