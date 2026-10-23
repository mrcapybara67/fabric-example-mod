package com.capy.client.module.modules;

import com.capy.client.config.ConfigValues;
import com.capy.client.gui.McCanvas;
import com.capy.client.gui.McType;
import com.capy.client.gui.UiCanvas;
import com.capy.client.gui.UiLabels;
import com.capy.client.gui.UiType;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.Category;
import com.capy.client.module.Configurable;
import com.capy.client.module.Module;
import com.capy.client.module.Tickable;
import com.capy.client.module.modules.weather.WeatherToast;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
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
 * Four details make the difference between a notifier and a spammer:
 *
 *  - the state is sampled on every tick even while the module is off, so
 *    switching it on never replays an old change;
 *  - the first tick in a world only records the current weather, so joining a
 *    server during a storm does not announce a storm that started hours ago;
 *  - a world change resets the sample, for the same reason;
 *  - two announcements closer together than {@link #MIN_GAP_MS} are collapsed
 *    into one, because a proxy or a dimension change can hand the client a
 *    clear tick in the middle of a storm.
 *
 * Thunder is a separate state, not a flag on rain: "rain" and "thunderstorm"
 * are what a player sees differently, and going from one to the other is worth
 * its own message.
 *
 * A change is reported three ways, each of which can be switched off: a chat
 * line, a card near the top of the screen, and a short sound. The card fades in
 * and out on its own and never blocks the view of the world.
 */
public class WeatherNotifierModule extends Module implements Tickable, Configurable {
	private static final Logger LOGGER = LoggerFactory.getLogger(WeatherNotifierModule.class);

	/** Two changes closer together than this are one change. */
	private static final long MIN_GAP_MS = 1_200L;

	/** What the sky is doing right now. */
	private enum State {
		/** No sample yet (first tick, or no world). */
		UNKNOWN,
		CLEAR,
		RAIN,
		THUNDER
	}

	/** Everything one transition needs to be announced. */
	private record Notice(String chatKey, String titleKey, String detailKey,
			String titleFallback, String detailFallback, WeatherToast.Kind kind,
			SoundEvent sound, float pitch) {
	}

	private final BooleanSetting chat = setting(new BooleanSetting("chat", true));
	private final BooleanSetting toast = setting(new BooleanSetting("toast", true));
	private final BooleanSetting sound = setting(new BooleanSetting("sound", true));
	private final BooleanSetting thunderOnly = setting(new BooleanSetting("thunder_only", false));

	/** The typefaces are created once and reused for every frame. */
	private static final UiType TYPE = new McType();

	private State last = State.UNKNOWN;
	private ClientLevel lastLevel;
	private long lastAnnounceAt;

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
			WeatherToast.hide();
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
		announce(previous, now);
	}

	private static State stateOf(ClientLevel level) {
		if (level.isThundering()) {
			return State.THUNDER;
		}
		return level.isRaining() ? State.RAIN : State.CLEAR;
	}

	private void announce(State from, State to) {
		if (thunderOnly.value() && from != State.THUNDER && to != State.THUNDER) {
			return;
		}
		Notice notice = noticeFor(from, to);
		if (notice == null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastAnnounceAt < MIN_GAP_MS) {
			return;
		}
		lastAnnounceAt = now;

		if (chat.value()) {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.player != null) {
				mc.player.displayClientMessage(line(notice.chatKey()), false);
			}
		}
		if (toast.value()) {
			WeatherToast.show(notice.kind(),
					UiLabels.translate(notice.titleKey(), notice.titleFallback()),
					UiLabels.translate(notice.detailKey(), notice.detailFallback()));
		}
		if (sound.value()) {
			playChime(notice);
		}
	}

	/**
	 * Everything one transition says. A single table, so the chat line, the
	 * card and the sound can never disagree about what just happened.
	 */
	private static Notice noticeFor(State from, State to) {
		return switch (to) {
			case THUNDER -> from == State.RAIN
					? new Notice("capy.weather.thunder_from_rain",
							"capy.weather.thunder_from_rain.title",
							"capy.weather.thunder_from_rain.detail",
							"THE RAIN TURNED TO A STORM", "thunder is rolling in",
							WeatherToast.Kind.THUNDER, SoundEvents.UI_TOAST_IN, 0.55f)
					: new Notice("capy.weather.thunder_start",
							"capy.weather.thunder_start.title",
							"capy.weather.thunder_start.detail",
							"A THUNDERSTORM HAS STARTED", "stay out of the open",
							WeatherToast.Kind.THUNDER, SoundEvents.UI_TOAST_IN, 0.55f);
			case RAIN -> from == State.THUNDER
					? new Notice("capy.weather.thunder_end",
							"capy.weather.thunder_end.title",
							"capy.weather.thunder_end.detail",
							"THE THUNDER HAS STOPPED", "the storm is easing",
							WeatherToast.Kind.RAIN, SoundEvents.UI_TOAST_OUT, 0.85f)
					: new Notice("capy.weather.rain_start",
							"capy.weather.rain_start.title",
							"capy.weather.rain_start.detail",
							"RAIN HAS STARTED", "the sky is closing in",
							WeatherToast.Kind.RAIN, SoundEvents.UI_TOAST_IN, 0.80f);
			case CLEAR -> from == State.THUNDER
					? new Notice("capy.weather.clear_after_storm",
							"capy.weather.clear_after_storm.title",
							"capy.weather.clear_after_storm.detail",
							"THE STORM HAS CLEARED", "clear skies ahead",
							WeatherToast.Kind.CLEAR, SoundEvents.UI_TOAST_OUT, 1.05f)
					: new Notice("capy.weather.rain_end",
							"capy.weather.rain_end.title",
							"capy.weather.rain_end.detail",
							"THE RAIN HAS STOPPED", "the clouds are thinning",
							WeatherToast.Kind.CLEAR, SoundEvents.UI_TOAST_OUT, 0.90f);
			default -> null;
		};
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

	private static void playChime(Notice notice) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.getSoundManager() == null || notice.sound() == null) {
			return;
		}
		try {
			mc.getSoundManager().play(SimpleSoundInstance.forUI(notice.sound(), 0.85f, notice.pitch()));
		} catch (RuntimeException e) {
			// A missing or broken sound must never take the client down.
			LOGGER.warn("Capy: could not play the Weather Notifier sound ({}).", e.toString());
		}
	}

	/** Draws the weather card over the HUD. */
	public void onHudRender(GuiGraphics graphics, int screenW) {
		if (!isEnabled() || !toast.value()) {
			return;
		}
		UiCanvas canvas = McCanvas.of(graphics);
		WeatherToast.render(canvas, TYPE.regular(), TYPE.medium(), screenW);
	}

	@Override
	protected void onToggle(boolean state) {
		if (!state) {
			// Leaving a card on screen after the module was switched off would
			// outlive the module that asked for it.
			WeatherToast.hide();
		}
	}

	// ---------------------------------------------------------- persistence

	@Override
	public void loadConfig(JsonObject json) {
		chat.set(ConfigValues.boolValue(json, "chat", true));
		toast.set(ConfigValues.boolValue(json, "toast", true));
		sound.set(ConfigValues.boolValue(json, "sound", true));
		thunderOnly.set(ConfigValues.boolValue(json, "thunder_only", false));
	}

	@Override
	public void saveConfig(JsonObject json) {
		json.addProperty("chat", chat.value());
		json.addProperty("toast", toast.value());
		json.addProperty("sound", sound.value());
		json.addProperty("thunder_only", thunderOnly.value());
	}
}
