package com.capy.client.antivanish.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VanishTextTest {
	@Test
	void acceptsJavaAndBedrockNames() {
		assertTrue(VanishText.plausibleName("Steve"));
		assertTrue(VanishText.plausibleName("_Notch123"));
		assertTrue(VanishText.plausibleName(".Alex"));
	}

	@Test
	void rejectsMalformedNames() {
		assertFalse(VanishText.plausibleName(null));
		assertFalse(VanishText.plausibleName("ab"));
		assertFalse(VanishText.plausibleName("abcdefghijklmnopq"));
		assertFalse(VanishText.plausibleName("Steve-"));
		assertFalse(VanishText.plausibleName("Steve Smith"));
		assertFalse(VanishText.plausibleName("..Alex"));
	}

	@Test
	void matchesNamesOnlyAsWholeWords() {
		assertTrue(VanishText.containsName("Steve joined the game", "Steve"));
		assertTrue(VanishText.containsName("[Steve] entered the lobby", "Steve"));
		assertTrue(VanishText.containsName("steve left the game", "Steve"));
		assertFalse(VanishText.containsName("Steve123 joined the game", "Steve"));
		assertFalse(VanishText.containsName("NotSteve joined the game", "Steve"));
		assertFalse(VanishText.containsName("Steve_ joined the game", "Steve"));
	}

	@Test
	void recognisesLeaveMessages() {
		assertTrue(VanishText.looksLikeLeave("Steve left the game", "Steve"));
		assertTrue(VanishText.looksLikeLeave("Steve logged out", "Steve"));
		assertTrue(VanishText.looksLikeLeave("Steve disconnected", "Steve"));
		assertFalse(VanishText.looksLikeLeave("Steve joined the game", "Steve"));
		assertFalse(VanishText.looksLikeLeave("Alex left the game", "Steve"));
		assertFalse(VanishText.looksLikeLeave(null, "Steve"));
	}
}
