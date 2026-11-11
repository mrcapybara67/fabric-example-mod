package com.capy.client.module.modules.movement;

import java.util.List;
import java.util.Locale;

/**
 * Recognises the language servers and anticheats use when they reject movement.
 *
 * <p>This is deliberately a phrase test, not a claim that a kick was caused by
 * a movement module. The caller only applies it when one was active moments
 * before, because a chat line that merely contains "flying" is otherwise far
 * too common.</p>
 */
public final class KickPhrase {
	private static final List<String> PHRASES = List.of(
			"moved too quickly",
			"moved too fast",
			"moved wrongly",
			"flying is not enabled",
			"flying is disabled",
			"flying too long",
			"vehicle moved too quickly",
			"boat moved too quickly",
			"kicked for flying",
			"kick for flying",
			"kicked by",
			"you have been kicked",
			"anticheat",
			"anti-cheat",
			"vulcan",
			"grim",
			"matrix",
			"aac",
			"ncp",
			"spartan",
			"sentinel",
			"movement check");

	private KickPhrase() {
	}

	public static boolean matches(String text) {
		if (text == null || text.isBlank()) {
			return false;
		}
		String lower = text.toLowerCase(Locale.ROOT);
		for (String phrase : PHRASES) {
			if (lower.contains(phrase)) {
				return true;
			}
		}
		return false;
	}
}
