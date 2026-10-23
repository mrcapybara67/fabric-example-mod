package com.capy.client.module.modules;

import com.capy.client.config.ConfigValues;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.Category;
import com.capy.client.module.Configurable;
import com.capy.client.module.Module;
import com.capy.client.module.Tickable;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Weather Notifier - announces the moment the weather changes.
 *
 * The client is told the weather by the server, so this reads the two flags the
 * level already carries ({@code isRaining}, {@code isThundering}) once per tick
 * and compares them with what it saw last. That is the only source that cannot
 * be wrong: it is the same state the rain particles and the sky are drawn from.
 *
 * Three details make the difference between a notifier and a spammer:
 *
 *  - the state is sampled on every tick even while the module is off, so
 *    switching it on never replays an old change;
 *  - the first tick in a world only records the current weather, so joining a
 *    server during a storm does not announce a storm that started hours ago;
 *  - a world change resets the sample, for the same reason.
 *
 * Thunder is a separate state, not a flag on rain: "rain" and "thunderstorm"
 * are what a player sees differently, and going from one to the other is worth
 * its own message.
 */
public class WeatherNotifierModule extends Module implements Tickable, Configurable {
	private static final Logger LOGGER = LoggerFactory.getLogger(WeatherNotifierModule.class);

	/** What the sky is doing right now. */
	private enum State {
		/** No sample yet (first tick, or no world). */
		UNKNOWN,
		CLEAR,
		RAIN,
		THUNDER
	}

	private final BooleanSetting chat = setting(new BooleanSetting("chat", true));
	private final BooleanSetting sound = setting(new BooleanSetting("sound", true));
	private final BooleanSetting thunderOnly = setting(new BooleanSetting("thunder_only", false));

	private State last = State.UNKNOWN;
	private ClientLevel lastLevel;

	public WeatherNotifierModule() {
		super(Category.WORLD, "weather_notifier");
	}

	@Override
	public void onTick(Minecraft mc) {
		ClientLevel level = mc == null ? null : mc.level;
		if (level == null) {
			last = State.UNKNOWN;
			lastLevel = null;
			return;
		}
		State now = stateOf(level);
		if (level != lastLevel) {
			// A new world: record where the weather is, do not announce it.
			lastLevel = level;
			last = now;
			return;
		}
		if (!isEnabled()) {
			// Keep the sample current so enabling the module later does not fire
			// a notification for a change that happened while it was off.
			last = now;
			return;
		}
		if (now == last) {
			return;
		}
		State previous = last;
		last = now;
		announce(mc, previous, now);
	}

	private static State stateOf(ClientLevel level) {
		if (level.isThundering()) {
			return State.THUNDER;
		}
		return level.isRaining() ? State.RAIN : State.CLEAR;
	}

	private void announce(Minecraft mc, State from, State to) {
		if (thunderOnly.value() && from != State.THUNDER && to != State.THUNDER) {
			return;
		}
		String key = messageKey(from, to);
		if (key == null) {
			return;
		}
		if (chat.value() && mc.player != null) {
			mc.player.displayClientMessage(line(key), false);
		}
		if (sound.value()) {
			playChime();
		}
	}

	/** The message for one transition, or null when there is nothing to say. */
	private static String messageKey(State from, State to) {
		switch (to) {
			case THUNDER:
				return from == State.RAIN ? "capy.weather.thunder_from_rain" : "capy.weather.thunder_start";
			case RAIN:
				return from == State.THUNDER ? "capy.weather.thunder_end" : "capy.weather.rain_start";
			case CLEAR:
				return from == State.THUNDER ? "capy.weather.clear_after_storm" : "capy.weather.rain_end";
			default:
				return null;
		}
	}

	/**
	 * One chat line. ASCII only on purpose: the client's own typeface is used
	 * by the GUI, but chat is drawn with vanilla's font, and a glyph it does
	 * not carry would show up as a box in every player's chat.
	 */
	private static Component line(String key) {
		return Component.literal("Capy ").withStyle(ChatFormatting.LIGHT_PURPLE)
				.append(Component.literal("| ").withStyle(ChatFormatting.DARK_GRAY))
				.append(Component.translatable(key).withStyle(ChatFormatting.GRAY));
	}

	private static void playChime() {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.getSoundManager() == null) {
			return;
		}
		try {
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_IN, 0.9f, 0.6f));
		} catch (RuntimeException e) {
			// A missing or broken sound must never take the client down.
			LOGGER.warn("Capy: could not play the Weather Notifier sound ({}).", e.toString());
		}
	}

	// ---------------------------------------------------------- persistence

	@Override
	public void loadConfig(JsonObject json) {
		chat.set(ConfigValues.boolValue(json, "chat", true));
		sound.set(ConfigValues.boolValue(json, "sound", true));
		thunderOnly.set(ConfigValues.boolValue(json, "thunder_only", false));
	}

	@Override
	public void saveConfig(JsonObject json) {
		json.addProperty("chat", chat.value());
		json.addProperty("sound", sound.value());
		json.addProperty("thunder_only", thunderOnly.value());
	}
}
