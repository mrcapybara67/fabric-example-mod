package com.capy.client.module;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.capy.client.finder.core.ColorMath;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class ColorSettingTest {
	@Test
	void oldIntegerOnlyConfigLoadsAsStatic() {
		JsonObject json = new JsonObject();
		json.addProperty("color", 0xFF123456);
		ColorSetting setting = new ColorSetting("color", 0xFFFFFFFF);

		setting.load(json, "color");

		assertEquals(0xFF123456, setting.argb());
		assertEquals(ColorMath.Mode.STATIC, setting.mode());
		assertEquals(1.0, setting.speed(), 1.0e-9);
	}

	@Test
	void animatedKeysAndRecentColorsRoundTrip() {
		ColorSetting source = new ColorSetting("color", 0xFFFFFFFF);
		source.set(0xFF102030);
		source.setMode(ColorMath.Mode.GRADIENT);
		source.setSecond(0xFF405060);
		source.setSpeed(2.25);
		source.remember(0xFFFF0000);
		source.remember(0xFF00FF00);
		source.remember(0xFFFF0000);

		JsonObject json = new JsonObject();
		source.save(json, "color");
		ColorSetting loaded = new ColorSetting("color", 0xFFFFFFFF);
		loaded.load(json, "color");

		assertEquals(0xFF102030, loaded.argb());
		assertEquals(ColorMath.Mode.GRADIENT, loaded.mode());
		assertEquals(0xFF405060, loaded.second());
		assertEquals(2.25, loaded.speed(), 1.0e-9);
		assertEquals(2, loaded.recentCount());
		assertEquals(0xFFFF0000, loaded.recentAt(0));
		assertEquals(0xFF00FF00, loaded.recentAt(1));
	}
}
