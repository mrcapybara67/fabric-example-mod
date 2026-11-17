package com.capy.client.antivanish;

import com.capy.client.antivanish.core.ProbePlanner;
import com.capy.client.antivanish.core.ProbeRunner;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;

/**
 * The only Anti Vanish class that sends anything to the server.
 *
 * <p>It sends a normal command-suggestion request for a watched name. The
 * packet is the same one vanilla uses while typing a command: it asks the
 * server to complete text, it does not execute a command and it does not put a
 * message in chat. The request is opt-in ({@code probe} is off by default) and
 * {@link ProbeRunner} keeps requests one at a time, paced by the humanized
 * {@link ProbePlanner}.</p>
 */
public final class VanishProbe {
	private static final AtomicInteger REQUEST_IDS = new AtomicInteger(1_000_000);
	private final ProbeRunner runner = new ProbeRunner(new ProbePlanner(new Random()), this::send);

	/** Replaces the names the next scheduled request may use. */
	public void setNames(List<String> names) {
		runner.setNames(names);
	}

	/**
	 * Advances the probe if it is enabled. A disabled probe never calls the
	 * transport, and no other class in the module sends a packet.
	 */
	public void tick(boolean enabled, Minecraft mc, long now) {
		if (!enabled) {
			runner.tick(false, now, Set.of());
			return;
		}
		if (mc == null || mc.getConnection() == null) {
			return;
		}
		Set<String> online = new HashSet<>();
		for (PlayerInfo info : mc.getConnection().getListedOnlinePlayers()) {
			if (info != null && info.getProfile() != null && info.getProfile().name() != null) {
				online.add(info.getProfile().name().toLowerCase(Locale.ROOT));
			}
		}
		runner.tick(true, now, online);
	}

	/** Passes one server answer to the request in flight. */
	public String acceptResponse(int id, List<String> names, long now) {
		return runner.acceptResponse(id, names, now);
	}

	/** Tells the planner that the server pushed back. */
	public void rateLimited(long now) {
		runner.rateLimited(now);
	}

	public void reset() {
		runner.reset();
	}

	public boolean pending() {
		return runner.pending();
	}

	private int send(String command) {
		Minecraft mc = Minecraft.getInstance();
		ClientPacketListener connection = mc == null ? null : mc.getConnection();
		if (connection == null || command == null || command.isBlank()) {
			return -1;
		}
		int id = REQUEST_IDS.getAndIncrement();
		// The suggestion packet carries command input without the leading
		// slash, exactly as vanilla's own suggestion provider does.
		connection.send(new ServerboundCommandSuggestionPacket(id, command));
		return id;
	}
}
