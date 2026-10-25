package com.capy.client.module.modules.antiadmin;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

/**
 * A player taken out of a scoreboard team with no leave message to go with it.
 *
 * The old vanish style is a team: hiding the nametag and dropping the player
 * from their team is enough to make them disappear from every client while the
 * entity stays in the world. A real disconnect is always announced, so a team
 * removal is counted as silent only when no line naming the player shows up in
 * the same breath.
 *
 * The decision is deferred by five ticks instead of being made immediately,
 * because the leave message and the team packet arrive in the same burst in
 * either order, and checking only the past would report every normal quit.
 */
public final class TeamPacketDetector extends AbstractDetector {
	private static final int DECISION_TICKS = 5;

	private final List<Pending> pending = new ArrayList<>();

	public TeamPacketDetector(SignalBuffer buffer) {
		super(buffer);
	}

	@Override
	public String name() {
		return "silent_team_remove";
	}

	@Override
	public DetectionKind kind() {
		return DetectionKind.SILENT_TEAM_REMOVE;
	}

	@Override
	public void reset() {
		pending.clear();
	}

	@Override
	public void onPacket(PacketSignals.Event event) {
		if (event instanceof PacketSignals.TeamRemove removal) {
			Minecraft mc = Minecraft.getInstance();
			for (String name : removal.names()) {
				PlayerInfo info = TabList.byName(mc, name);
				UUID id = info == null || info.getProfile() == null ? null : info.getProfile().id();
				pending.add(new Pending(id, name, removal.team(), buffer.tick() + DECISION_TICKS));
			}
		} else if (event instanceof PacketSignals.SystemChat chat) {
			acknowledge(chat.text());
		}
	}

	@Override
	public void onTick(Minecraft mc) {
		if (pending.isEmpty()) {
			return;
		}
		long now = buffer.tick();
		for (Iterator<Pending> iterator = pending.iterator(); iterator.hasNext(); ) {
			Pending entry = iterator.next();
			if (now < entry.dueTick) {
				continue;
			}
			iterator.remove();
			if (entry.uuid != null) {
				emit(entry.uuid, null,
						"removed from team \"" + entry.team + "\" with no leave message");
			}
		}
	}

	/**
	 * Drops any pending removal the line accounts for.
	 *
	 * A leave message names the account and says they left; that is enough to
	 * treat the team packet as the bookkeeping it is.
	 */
	private void acknowledge(String text) {
		if (text == null || text.isEmpty() || pending.isEmpty()) {
			return;
		}
		String lower = text.toLowerCase(Locale.ROOT);
		if (!lower.contains("left") && !lower.contains("quit") && !lower.contains("disconnect")) {
			return;
		}
		for (Iterator<Pending> iterator = pending.iterator(); iterator.hasNext(); ) {
			Pending entry = iterator.next();
			if (lower.contains(entry.name.toLowerCase(Locale.ROOT))) {
				iterator.remove();
			}
		}
	}

	private record Pending(UUID uuid, String name, String team, long dueTick) {
	}
}
