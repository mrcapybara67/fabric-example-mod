package com.capy.client.module.modules.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class MovementProfileTest {
	@Test
	void serverProfilePersistsAndLoads() {
		MovementProfiles profiles = MovementProfiles.get();
		profiles.clear();
		profiles.profile("cookiesmp.my.id").lower();
		JsonObject saved = profiles.toJson();
		double expectedHorizontal = profiles.profile("cookiesmp.my.id").horizontal();
		double expectedVertical = profiles.profile("cookiesmp.my.id").vertical();

		profiles.clear();
		profiles.load(saved);

		MovementProfile loaded = profiles.profile("cookiesmp.my.id");
		assertEquals(expectedHorizontal, loaded.horizontal(), 1.0e-9);
		assertEquals(expectedVertical, loaded.vertical(), 1.0e-9);
	}

	@Test
	void profilesHaveABoundedMap() {
		MovementProfiles profiles = MovementProfiles.get();
		profiles.clear();
		for (int i = 0; i < 100; i++) {
			profiles.profile("server-" + i);
		}
		assertTrue(profiles.size() <= 64, "old hosts are evicted instead of growing forever");
	}

	@Test
	void kickPhrasesRecogniseDisconnectAndChatLanguage() {
		assertTrue(KickPhrase.matches("You were kicked by Vulcan: moved too quickly"));
		assertTrue(KickPhrase.matches("Flying is not enabled on this server"));
		assertTrue(KickPhrase.matches("[Grim] movement check failed"));
		assertTrue(KickPhrase.matches("moved too fast"));
		assertTrue(!KickPhrase.matches("Welcome to the server"));
	}
}
