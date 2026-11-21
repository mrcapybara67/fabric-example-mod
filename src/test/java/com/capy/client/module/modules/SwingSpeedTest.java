package com.capy.client.module.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class SwingSpeedTest {
	@Test
	void singleAndContinuousSwingsUseTheSameScaledDuration() {
		SwingSpeedModule module = new SwingSpeedModule();
		module.loadConfig(new JsonObject());
		int vanilla = 6;
		int scaled = module.scaleSwingDuration(vanilla);
		assertEquals(6, scaled);
		module.loadConfig(config(5));
		int single = module.scaleSwingDuration(vanilla);
		int continuous = module.scaleSwingDuration(vanilla);
		assertEquals(single, continuous);
		assertEquals(12, single);
	}

	@Test
	void fastSwingsStillHaveASecondFrame() {
		SwingSpeedModule module = new SwingSpeedModule();
		module.loadConfig(config(20));

		assertEquals(3, module.scaleSwingDuration(6));
		assertEquals(SwingSpeedModule.MIN_SWING_TICKS, module.scaleSwingDuration(1));
		assertEquals(SwingSpeedModule.MIN_SWING_TICKS, module.scaleSwingDuration(2));
		assertTrue(module.scaleSwingDuration(3) >= SwingSpeedModule.MIN_SWING_TICKS);
	}

	private static JsonObject config(int speed) {
		JsonObject json = new JsonObject();
		json.addProperty("speed", speed);
		return json;
	}
}
