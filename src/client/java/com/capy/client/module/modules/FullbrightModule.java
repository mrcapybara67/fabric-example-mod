package com.capy.client.module.modules;

import com.capy.client.config.ConfigValues;
import com.capy.client.module.Category;
import com.capy.client.module.Configurable;
import com.capy.client.module.Module;
import com.capy.client.module.SliderSetting;
import com.google.gson.JsonObject;

/**
 * Fullbright — the whole world lit as if every block were under an open sky.
 *
 * The brightness lives in one number: the gamma the lightmap adds to every
 * sampled light level. Vanilla caps it at 1.0, which is the brightest the
 * slider in the video settings can go; anything above that is what turns a cave
 * from "dim" into "daylight". The module therefore exposes 1 to 100 and the
 * lightmap mixin reads it (see {@code LightTextureMixin} for why writing the
 * option itself is not possible).
 *
 * Nothing is stored outside the module: the option, the options file and the
 * player's own gamma setting are never touched, so switching the module off is
 * an instant, complete undo.
 */
public class FullbrightModule extends Module implements Configurable {
	public static final int MIN_GAMMA = 1;
	public static final int MAX_GAMMA = 100;
	public static final int DEFAULT_GAMMA = 100;

	private final SliderSetting gamma = setting(new SliderSetting("gamma", DEFAULT_GAMMA, MIN_GAMMA, MAX_GAMMA));

	public FullbrightModule() {
		super(Category.VISUAL, "fullbright");
	}

	/** The gamma the lightmap is built with while the module is on. */
	public double gamma() {
		return gamma.get();
	}

	// ---------------------------------------------------------- persistence

	@Override
	public void loadConfig(JsonObject json) {
		gamma.set(ConfigValues.intValue(json, "gamma", DEFAULT_GAMMA));
	}

	@Override
	public void saveConfig(JsonObject json) {
		json.addProperty("gamma", gamma.get());
	}
}
