package com.capy.client.module.modules.antiadmin;

import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * A short memory of names that appeared in server system messages.
 *
 * <p>Only server messages are evidence. A player can type any name, and the
 * local player's own command is not an observation about anybody; both are
 * ignored. Join and leave lines are excluded from the weak presence hint as
 * well, because a normal logout must not leave a name looking vanished for
 * three minutes. Leave lines are kept separately so an alert can be cancelled
 * inside the short logout window.</p>
 *
 * <p>Whole-name matching keeps "Sam" out of "Samsung". The queue is bounded, so
 * a server that floods chat cannot grow this memory without limit.</p>
 */
public final class ChatPresence {
	private static final int MAX_LINES = 96;
	private static final int MAX_LEAVES = 64;
	private static final long WINDOW_MS = 180_000L;
	/** A normal logout is cancelled around this window, in milliseconds. */
	public static final long LEAVE_WINDOW_MS = 4_000L;

	private static final ConcurrentLinkedDeque<Line> LINES = new ConcurrentLinkedDeque<>();
	private static final ConcurrentLinkedDeque<Line> LEAVES = new ConcurrentLinkedDeque<>();

	private ChatPresence() {
	}

	/**
	 * Records one server system line.
	 *
	 * <p>Join and leave lines are not stored as presence hints. A leave line is
	 * stored in the short logout window instead; a join line is ignored because
	 * being listed already answers that question.</p>
	 */
	public static void recordSystem(String text) {
		if (text == null || text.isBlank()) {
			return;
		}
		String lower = text.toLowerCase(Locale.ROOT);
		long now = System.currentTimeMillis();
		if (isLeave(lower)) {
			LEAVES.addLast(new Line(lower, now));
			trim(LEAVES, MAX_LEAVES);
			return;
		}
		if (isJoin(lower)) {
			return;
		}
		LINES.addLast(new Line(lower, now));
		trim(LINES, MAX_LINES);
	}

	/**
	 * Commands are deliberately not evidence.
	 *
	 * <p>Kept as an explicit no-op so a caller that still reports an outgoing
	 * command cannot accidentally resurrect the old behavior.</p>
	 */
	public static void recordCommand(String command) {
		// Outgoing commands are not observations about another account.
	}

	/**
	 * True when {@code name} was named as a whole word in a retained server
	 * line. This is a weak hint, never a presence proof.
	 */
	public static boolean recent(String name, long windowMs) {
		if (name == null || name.isBlank()) {
			return false;
		}
		long now = System.currentTimeMillis();
		long window = windowMs <= 0L ? WINDOW_MS : windowMs;
		String needle = name.toLowerCase(Locale.ROOT);
		for (Iterator<Line> it = LINES.descendingIterator(); it.hasNext();) {
			Line line = it.next();
			if (now - line.timeMs() > window) {
				return false;
			}
			if (containsWord(line.lower(), needle)) {
				return true;
			}
		}
		return false;
	}

	/** True when a server leave line named {@code name} in the last window. */
	public static boolean leaveWithin(String name, long windowMs) {
		if (name == null || name.isBlank()) {
			return false;
		}
		long now = System.currentTimeMillis();
		long window = windowMs <= 0L ? LEAVE_WINDOW_MS : windowMs;
		String needle = name.toLowerCase(Locale.ROOT);
		for (Iterator<Line> it = LEAVES.descendingIterator(); it.hasNext();) {
			Line line = it.next();
			if (now - line.timeMs() > window) {
				return false;
			}
			if (containsWord(line.lower(), needle)) {
				return true;
			}
		}
		return false;
	}

	/** Drops all retained text. Called on a world change or a toggle. */
	public static void clear() {
		LINES.clear();
		LEAVES.clear();
	}

	private static void trim(ConcurrentLinkedDeque<Line> lines, int max) {
		while (lines.size() > max) {
			lines.pollFirst();
		}
	}

	private static boolean isLeave(String lower) {
		return lower.contains("left the game") || lower.contains("left the server")
				|| lower.contains("logged out") || lower.contains("disconnected");
	}

	private static boolean isJoin(String lower) {
		return lower.contains("joined the game") || lower.contains("joined the server")
				|| lower.contains("logged in");
	}

	private static boolean containsWord(String haystack, String word) {
		int from = 0;
		while (true) {
			int at = haystack.indexOf(word, from);
			if (at < 0) {
				return false;
			}
			int end = at + word.length();
			boolean leftEdge = at == 0 || !isNameChar(haystack.charAt(at - 1));
			boolean rightEdge = end >= haystack.length() || !isNameChar(haystack.charAt(end));
			if (leftEdge && rightEdge) {
				return true;
			}
			from = at + 1;
		}
	}

	private static boolean isNameChar(char ch) {
		return Character.isLetterOrDigit(ch) || ch == '_';
	}

	private record Line(String lower, long timeMs) {
	}
}
