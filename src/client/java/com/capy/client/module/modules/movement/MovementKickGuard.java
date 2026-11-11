package com.capy.client.module.modules.movement;

import com.capy.client.module.modules.antiadmin.ServerProfileMatcher;
import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Learns from a movement-related disconnect.
 *
 * <p>It does not decide that the server was right; it records that a movement
 * module was active in the last few seconds and that a kick-shaped line
 * arrived, then lowers that host's profile one step. The line is checked
 * against both the disconnect reason and the last system chat lines, because
 * some servers put the reason in chat before closing the connection.</p>
 */
public final class MovementKickGuard {
	private static final long ACTIVE_WINDOW_NANOS = 15_000_000_000L;
	private static final long PENALTY_COOLDOWN_NANOS = 6_000_000_000L;
	private static final int CHAT_HISTORY = 8;

	private static final Deque<String> LAST_CHAT = new ArrayDeque<>();
	private static long lastMovementNanos;
	private static long lastPenaltyNanos;

	private MovementKickGuard() {
	}

	/** Called by a movement module while it is actually driving the body. */
	public static void noteActive() {
		lastMovementNanos = System.nanoTime();
	}

	public static void onSystemChat(String text) {
		if (text == null || text.isBlank()) {
			return;
		}
		synchronized (LAST_CHAT) {
			LAST_CHAT.addLast(text);
			while (LAST_CHAT.size() > CHAT_HISTORY) {
				LAST_CHAT.removeFirst();
			}
		}
		if (KickPhrase.matches(text)) {
			penalise(text);
		}
	}

	public static void onDisconnect(String reason) {
		boolean hadMovement = System.nanoTime() - lastMovementNanos <= ACTIVE_WINDOW_NANOS;
		StringBuilder all = new StringBuilder(reason == null ? "" : reason);
		synchronized (LAST_CHAT) {
			for (String line : LAST_CHAT) {
				all.append('\n').append(line);
			}
		}
		if (hadMovement && KickPhrase.matches(all.toString())) {
			penalise(all.toString());
		}
		clearSession();
	}

	public static void resetSession() {
		clearSession();
	}

	private static void penalise(String evidence) {
		long now = System.nanoTime();
		if (now - lastMovementNanos > ACTIVE_WINDOW_NANOS
				|| now - lastPenaltyNanos < PENALTY_COOLDOWN_NANOS) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		String host = ServerProfileMatcher.hostOf(mc);
		if (host == null || host.isBlank()) {
			return;
		}
		MovementProfile profile = MovementProfiles.get().lower(host);
		lastPenaltyNanos = now;
		if (mc != null && mc.player != null) {
			mc.player.displayClientMessage(Component.literal(
					"[Capy] Movement kick matched; lowered " + host + " to "
							+ String.format(java.util.Locale.ROOT, "%.2f", profile.horizontal())
							+ " / "
							+ String.format(java.util.Locale.ROOT, "%.2f", profile.vertical())
							+ " blocks/s. " + shortEvidence(evidence)), false);
		}
	}

	private static String shortEvidence(String evidence) {
		if (evidence == null) {
			return "";
		}
		String oneLine = evidence.replace('\n', ' ').strip();
		return oneLine.length() <= 80 ? oneLine : oneLine.substring(0, 80) + "…";
	}

	private static void clearSession() {
		synchronized (LAST_CHAT) {
			LAST_CHAT.clear();
		}
		lastMovementNanos = 0L;
	}
}
