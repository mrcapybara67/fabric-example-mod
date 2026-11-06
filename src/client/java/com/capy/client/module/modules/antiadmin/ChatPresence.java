package com.capy.client.module.modules.antiadmin;

import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * A short memory of names that appeared in chat or in a command the player
 * typed.
 *
 * <p>This exists for the case no packet can solve: a staff member in
 * {@code /vanish} whose account the server removed from the tab list and whose
 * entity it stopped sending. If the server still talks about them - a teleport
 * request, a join notice, a moderator line - the client has one piece of
 * evidence left. The staff list and Anti Admin read it instead of claiming the
 * player is offline.</p>
 *
 * <p>Only names that already appear on a watchlist are ever matched, so a common
 * word in chat cannot create a row by accident.</p>
 */
public final class ChatPresence {
	private static final int MAX_LINES = 96;
	private static final long WINDOW_MS = 180_000L;

	private static final ConcurrentLinkedDeque<Line> LINES = new ConcurrentLinkedDeque<>();

	private ChatPresence() {
	}

	/** Remembers one chat or system line. */
	public static void record(String text) {
		if (text == null || text.isBlank()) {
			return;
		}
		LINES.addLast(new Line(text.toLowerCase(Locale.ROOT), System.currentTimeMillis()));
		while (LINES.size() > MAX_LINES) {
			LINES.pollFirst();
		}
	}

	/** Remembers an outgoing command such as {@code /tpa Steve}. */
	public static void recordCommand(String command) {
		if (command == null || command.isBlank()) {
			return;
		}
		record(command.startsWith("/") ? command.substring(1) : command);
	}

	/**
	 * True when {@code name} was named as a whole word in the last three
	 * minutes. Whole-word matching keeps "Sam" out of "Samsung".
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
