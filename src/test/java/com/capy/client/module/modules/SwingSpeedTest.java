package com.capy.client.module.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SwingSpeedTest {
	@Test
	void singleAndContinuousSwingsUseTheSameScaledDuration() {
		SwingSpeedModule module = new SwingSpeedModule();
		module.loadConfig(new com.google.gson.JsonObject());
		int vanilla = 6;
		int scaled = module.scaleSwingDuration(vanilla);
		assertEquals(6, scaled);
		com.google.gson.JsonObject slow = new com.google.gson.JsonObject();
		slow.addProperty("speed", 5);
		module.loadConfig(slow);
		int single = module.scaleSwingDuration(vanilla);
		int continuous = module.scaleSwingDuration(vanilla);
		assertEquals(single, continuous);
		assertEquals(12, single);
	}
}
