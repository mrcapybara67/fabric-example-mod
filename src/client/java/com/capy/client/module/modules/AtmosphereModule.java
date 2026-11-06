package com.capy.client.module.modules;

import com.capy.client.config.ConfigValues;
import com.capy.client.module.Category;
import com.capy.client.module.ChoiceSetting;
import com.capy.client.module.ColorSetting;
import com.capy.client.module.Configurable;
import com.capy.client.module.Module;
import com.capy.client.module.SliderSetting;
import com.capy.client.module.Tickable;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

/**
 * Atmosphere — the sky, the fog, the weather and the light, changed where
 * vanilla computes them instead of painted over the frame.
 *
 * <h2>Why this replaces an overlay sky</h2>
 * A painted sky is a picture hung in front of the camera. It is drawn after the
 * world, so it cannot mix with the terrain's haze, it does not dim the light on
 * the ground, it does not move the sun, and the moment the player looks at a
 * horizon the painting and the fog disagree about where the world ends. This
 * module instead writes the values the vanilla renderer already reads: the sky
 * colour it puts in its render state, the fog distances it uploads, the day
 * time it derives the sun angle from, and the lightmap brightness. The renderer
 * then produces the atmosphere itself, which is why it is smooth and why it
 * stacks with rain, sunrise and underwater fog the way the game does.
 *
 * <h2>Presets and sliders</h2>
 * A theme is a starting point, not a mode: selecting one loads its tint and its
 * time and weather, and every one of those can then be moved with the sliders
 * and pickers below. Changing a slider never changes the theme, and changing
 * the theme never changes a slider, so the two cannot fight over the same
 * value.
 *
 * <h2>Where the values live</h2>
 * The module writes {@link AtmosphereState} on the tick; the mixins read it on
 * the render thread. Nothing here touches the world, sends a packet, or writes
 * to the options file, so switching the module off restores vanilla on the next
 * frame.
 */
public final class AtmosphereModule extends Module implements Tickable, Configurable {
	private static final String[] THEMES = {
			"default", "golden_hour", "midnight", "aurora", "overcast",
			"deep_ocean", "crimson", "sakura", "void", "neon"
	};
	private static final String[] TIME_MODES = {
			"real", "sunrise", "morning", "noon", "afternoon", "sunset", "dusk", "midnight"
	};
	private static final String[] WEATHER_MODES = {"real", "clear", "rain", "thunder"};

	/**
	 * Where each time label sits in {@link AtmosphereState}'s convention
	 * (0 = midnight, 0.25 = sunrise, 0.5 = noon, 0.75 = sunset). The hook
	 * converts that to vanilla's sunrise-based clock.
	 */
	private static final float[] TIME_FRACTIONS = {
			-1f, 0.25f, 0.375f, 0.5f, 0.625f, 0.75f, 0.80f, 0.0f
	};

	private final ChoiceSetting theme = setting(new ChoiceSetting("theme", 0, THEMES));
	private final SliderSetting skyBlend = setting(new SliderSetting("sky_blend", 70, 0, 100));
	private final SliderSetting fogDensity = setting(new SliderSetting("fog_density", 100, 20, 300));
	private final SliderSetting bloom = setting(new SliderSetting("bloom", 40, 0, 100));
	private final SliderSetting warmth = setting(new SliderSetting("warmth", 30, 0, 100));
	private final SliderSetting brightness = setting(new SliderSetting("brightness", 100, 50, 200));
	private final ChoiceSetting timeMode = setting(new ChoiceSetting("time", 0, TIME_MODES));
	private final ChoiceSetting weatherMode = setting(new ChoiceSetting("weather", 0, WEATHER_MODES));
	private final ColorSetting skyTint = setting(new ColorSetting("sky_tint", 0xFF8FB8FF));
	private final ColorSetting hazeTint = setting(new ColorSetting("haze_tint", 0xFFFFD980));

	private long lastNanos;
	/** The theme whose preset has already been loaded into the settings. */
	private int appliedTheme = -1;

	public AtmosphereModule() {
		super(Category.VISUAL, "atmosphere");
	}

	@Override
	protected void onToggle(boolean state) {
		AtmosphereState.active = state;
		lastNanos = 0L;
		if (state) {
			// The theme is not re-applied here on purpose. A preset is loaded when
			// the selection changes and when a saved config is read; reloading it
			// on every enable would silently undo the sky and haze colours the
			// player picked after choosing the preset.
			return;
		}
		// Off is vanilla on the next frame: every hook checks the flag, and the
		// targets are dropped so a re-enable does not ease out of a stale value.
		AtmosphereState.neutral();
		AtmosphereState.skyHot = 0f;
		AtmosphereState.fogHot = 1f;
		AtmosphereState.fbAmb = 0f;
		AtmosphereState.fbHot = 0f;
		AtmosphereState.fbLive = 1f;
		AtmosphereState.timeDay = -1f;
		AtmosphereState.rainOvr = -1f;
		AtmosphereState.thunderOvr = -1f;
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!isEnabled()) {
			return;
		}
		// Defensive: a config restore sets the toggle, but a module that is
		// enabled without its toggle having run must still light the hooks.
		AtmosphereState.active = true;

		long now = System.nanoTime();
		float dt = lastNanos == 0L ? 0.05f : (now - lastNanos) / 1.0e9f;
		lastNanos = now;
		dt = Math.max(0f, Math.min(0.1f, dt));

		int selected = theme.index();
		if (selected != appliedTheme) {
			applyTheme(selected);
			appliedTheme = selected;
		}

		// Sliders drive the strengths. Each is stored in hundredths so the GUI
		// has integer steps; `real()` gives the 0..1 (or 0.2..3) value back.
		AtmosphereState.skyHotTarget = (float) skyBlend.real() / 100f;
		AtmosphereState.fogHotTarget = (float) fogDensity.real() / 100f;
		AtmosphereState.fbHotTarget = (float) bloom.real() / 100f;
		AtmosphereState.fbAmbTarget = (float) warmth.real() / 100f;
		AtmosphereState.fbLiveTarget = (float) brightness.real() / 100f;

		int sky = skyTint.argb();
		AtmosphereState.skyR = ((sky >> 16) & 0xFF) / 255f;
		AtmosphereState.skyG = ((sky >> 8) & 0xFF) / 255f;
		AtmosphereState.skyB = (sky & 0xFF) / 255f;

		int haze = hazeTint.argb();
		AtmosphereState.hazeR = ((haze >> 16) & 0xFF) / 255f;
		AtmosphereState.hazeG = ((haze >> 8) & 0xFF) / 255f;
		AtmosphereState.hazeB = (haze & 0xFF) / 255f;

		AtmosphereState.timeDayTarget = TIME_FRACTIONS[Math.min(timeMode.index(), TIME_FRACTIONS.length - 1)];
		applyWeather(weatherMode.index());

		AtmosphereState.tick(dt);
		// Theme weather is vanilla particles spawned on the client: no packet,
		// no server simulation, and it goes through the game's own particle
		// renderer, so a replaced renderer still draws it.
		AtmosphereEffects.tick(mc, selected);
	}

	/** Rain and thunder strengths for a weather choice. */
	private static void applyWeather(int index) {
		switch (index) {
			case 1 -> {
				AtmosphereState.rainOvrTarget = 0f;
				AtmosphereState.thunderOvrTarget = 0f;
			}
			case 2 -> {
				AtmosphereState.rainOvrTarget = 0.8f;
				AtmosphereState.thunderOvrTarget = 0f;
			}
			case 3 -> {
				AtmosphereState.rainOvrTarget = 1f;
				AtmosphereState.thunderOvrTarget = 1f;
			}
			default -> {
				AtmosphereState.rainOvrTarget = -1f;
				AtmosphereState.thunderOvrTarget = -1f;
			}
		}
	}

	/**
	 * Loads a preset into the settings that describe it.
	 *
	 * <p>Only colours and the two choice rows. The sliders are left exactly
	 * where the player put them: a preset that also reset the blend and the fog
	 * would make every slider feel like it had been ignored, and the sliders are
	 * the part of the card people actually tune.</p>
	 */
	private void applyTheme(int index) {
		switch (index) {
			case 1 -> { // golden hour
				skyTint.set(0xFFFFB877);
				hazeTint.set(0xFFFFC46B);
				timeMode.set(5);
				weatherMode.set(1);
			}
			case 2 -> { // midnight
				skyTint.set(0xFF1B2342);
				hazeTint.set(0xFF5D74C8);
				timeMode.set(7);
				weatherMode.set(1);
			}
			case 3 -> { // aurora
				skyTint.set(0xFF5CF7C4);
				hazeTint.set(0xFF8FF0FF);
				timeMode.set(7);
				weatherMode.set(1);
			}
			case 4 -> { // overcast
				skyTint.set(0xFF8E9AA8);
				hazeTint.set(0xFFAEB8C4);
				timeMode.set(0);
				weatherMode.set(2);
			}
			case 5 -> { // deep ocean
				skyTint.set(0xFF2E6E9E);
				hazeTint.set(0xFF7CC6E8);
				timeMode.set(3);
				weatherMode.set(1);
			}
			case 6 -> { // crimson
				skyTint.set(0xFFB31E2A);
				hazeTint.set(0xFFFF6A5A);
				timeMode.set(5);
				weatherMode.set(1);
			}
			case 7 -> { // sakura
				skyTint.set(0xFFFFB7D2);
				hazeTint.set(0xFFFFE1EC);
				timeMode.set(1);
				weatherMode.set(1);
			}
			case 8 -> { // void
				skyTint.set(0xFF08080F);
				hazeTint.set(0xFF242438);
				timeMode.set(7);
				weatherMode.set(1);
			}
			case 9 -> { // neon
				skyTint.set(0xFF7A22C8);
				hazeTint.set(0xFF54FFE8);
				timeMode.set(7);
				weatherMode.set(1);
			}
			default -> { // default
				skyTint.set(0xFF8FB8FF);
				hazeTint.set(0xFFFFD980);
				timeMode.set(0);
				weatherMode.set(0);
			}
		}
	}

	// ---------------------------------------------------------- persistence

	@Override
	public void loadConfig(JsonObject json) {
		readChoice(json, "theme", theme);
		readChoice(json, "time", timeMode);
		readChoice(json, "weather", weatherMode);
		skyBlend.set(ConfigValues.intValue(json, "sky_blend", 70));
		fogDensity.set(ConfigValues.intValue(json, "fog_density", 100));
		bloom.set(ConfigValues.intValue(json, "bloom", 40));
		warmth.set(ConfigValues.intValue(json, "warmth", 30));
		brightness.set(ConfigValues.intValue(json, "brightness", 100));
		skyTint.set(ConfigValues.intValue(json, "sky_tint", 0xFF8FB8FF));
		hazeTint.set(ConfigValues.intValue(json, "haze_tint", 0xFFFFD980));
		// A stored preset is already loaded; do not load it again over a colour
		// the player may have picked afterwards.
		appliedTheme = theme.index();
	}

	@Override
	public void saveConfig(JsonObject json) {
		json.addProperty("theme", theme.option());
		json.addProperty("time", timeMode.option());
		json.addProperty("weather", weatherMode.option());
		json.addProperty("sky_blend", skyBlend.get());
		json.addProperty("fog_density", fogDensity.get());
		json.addProperty("bloom", bloom.get());
		json.addProperty("warmth", warmth.get());
		json.addProperty("brightness", brightness.get());
		json.addProperty("sky_tint", skyTint.argb());
		json.addProperty("haze_tint", hazeTint.argb());
	}

	/** Reads a choice by id, leaving the default when the stored id is gone. */
	private static void readChoice(JsonObject json, String key, ChoiceSetting setting) {
		if (json.has(key) && json.get(key).isJsonPrimitive()) {
			setting.select(json.get(key).getAsString());
		}
	}
}
