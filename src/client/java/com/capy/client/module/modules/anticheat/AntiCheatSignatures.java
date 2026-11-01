package com.capy.client.module.modules.anticheat;

import java.util.List;
import java.util.Locale;

/**
 * What a server's own name and address give away about its anticheat.
 *
 * <p>This is the only evidence a client has without acting: an anticheat does
 * not announce itself with a packet. What it does do is appear in the address
 * people connect to ({@code play.grim.ac}, {@code vulcan.example.com}), in the
 * server's list name ({@code "Grim 2.0 Practice"}) and in the names of the
 * plugins the network advertises. Those strings are the fingerprints below.
 *
 * <p>Matching is deliberately conservative. A short needle such as {@code ncp}
 * or {@code aac} is only accepted as a whole token, because as a substring it
 * matches half the hostnames on the internet, and a notifier that says "there
 * is no anticheat here" when there is one is worse than one that stays quiet.
 * Longer needles are matched as substrings, which is what catches
 * {@code grimac}, {@code grim-anticheat} and {@code nocheetplus}.
 */
public final class AntiCheatSignatures {
	/** One anticheat, and the strings that name it. */
	public record Signature(String name, List<String> needles) {
	}

	private static final List<Signature> KNOWN = List.of(
			// "grim" alone is short, so it only matches as a whole hostname
			// token; the longer aliases cover the hosts that spell it out.
			new Signature("Grim", List.of("grimac", "grim-ac", "grim")),
			new Signature("Vulcan", List.of("vulcan")),
			new Signature("Matrix", List.of("matrix")),
			new Signature("Spartan", List.of("spartan")),
			new Signature("Verus", List.of("verus")),
			new Signature("Intave", List.of("intave")),
			new Signature("Karhu", List.of("karhu")),
			new Signature("Buzz", List.of("buzzac", "buzz")),
			new Signature("NoCheatPlus", List.of("nocheatplus", "ncp")),
			new Signature("AAC", List.of("advancedanticheat", "aac")),
			new Signature("Reflex", List.of("reflex")),
			new Signature("Polar", List.of("polar")),
			new Signature("Watchdog", List.of("watchdog")),
			new Signature("FairPlay", List.of("fairplay")),
			new Signature("AntiAura", List.of("antiaura")),
			new Signature("Themis", List.of("themis")),
			new Signature("Negativity", List.of("negativity")),
			new Signature("Horizon", List.of("horizon")));

	private AntiCheatSignatures() {
	}

	/**
	 * The anticheat named by a string, or null when it names none.
	 *
	 * @param text a hostname, a server list name or any other server supplied
	 *             text; null and blank are accepted
	 */
	public static String match(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		String lower = text.toLowerCase(Locale.ROOT);
		for (Signature signature : KNOWN) {
			for (String needle : signature.needles()) {
				if (contains(lower, needle)) {
					return signature.name();
				}
			}
		}
		return null;
	}

	/** Every known name, for the module's documentation and its log line. */
	public static List<String> names() {
		return KNOWN.stream().map(Signature::name).toList();
	}

	/**
	 * A long needle anywhere in the text; a short one only as a whole token.
	 *
	 * <p>The threshold is where a substring stops being distinctive: {@code grim}
	 * inside {@code pilgrim.example.com} is a false positive nobody wants, but
	 * {@code grim} inside {@code grimac.net} is the whole point.
	 */
	private static boolean contains(String text, String needle) {
		if (needle.length() >= 5) {
			return text.contains(needle);
		}
		for (String token : text.split("[^a-z0-9]+")) {
			if (token.equals(needle)) {
				return true;
			}
		}
		return false;
	}
}
