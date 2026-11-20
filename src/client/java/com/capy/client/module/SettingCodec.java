package com.capy.client.module;

import com.capy.client.config.ConfigValues;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;

/**
 * Generic persistence for every setting type.
 *
 * <p>Before this existed each module wrote its own {@code loadConfig} and
 * {@code saveConfig} by hand, which meant a setting could be added to a card
 * and silently never survive a restart. This codec walks the registered
 * settings and stores each one under its own key, using the same layout the
 * modules already wrote: integers and booleans as primitives, choice values by
 * option id, colours through {@link ColorSetting#load}, and lists as arrays.</p>
 *
 * <p>Module-specific loaders still run afterwards. That order is deliberate:
 * the generic pass loads every current key, then the module can translate an
 * old key or lift a legacy default, and its write wins.</p>
 */
public final class SettingCodec {
	private SettingCodec() {
	}

	/** Loads one setting from {@code json}, falling back to its default. */
	public static void load(Setting<?> setting, JsonObject json) {
		if (setting == null || setting instanceof ActionSetting) {
			return;
		}
		String key = setting.key();
		if (setting instanceof ColorSetting color) {
			color.load(json, key);
			return;
		}
		if (setting instanceof BooleanSetting bool) {
			bool.set(ConfigValues.boolValue(json, key, fallbackBool(bool)));
			return;
		}
		if (setting instanceof ChoiceSetting choice) {
			String stored = ConfigValues.stringValue(json, key, null);
			if (stored != null) {
				choice.select(stored);
			}
			return;
		}
		if (setting instanceof SliderSetting slider) {
			slider.set(ConfigValues.intValue(json, key, fallbackInt(slider)));
			return;
		}
		if (setting instanceof StringSetting text) {
			text.set(ConfigValues.stringValue(json, key, fallbackString(text)));
			return;
		}
		if (setting instanceof BlockListSetting blocks) {
			if (hasArray(json, key)) {
				blocks.set(ConfigValues.stringList(json, key));
			}
			return;
		}
		if (setting instanceof WatchlistSetting watch) {
			if (hasArray(json, key)) {
				watch.set(ConfigValues.stringList(json, key));
			}
		}
	}

	/** Writes one setting under its own key. */
	public static void save(Setting<?> setting, JsonObject json) {
		if (setting == null || json == null || setting instanceof ActionSetting) {
			return;
		}
		String key = setting.key();
		if (setting instanceof ColorSetting color) {
			color.save(json, key);
			return;
		}
		if (setting instanceof BooleanSetting bool) {
			json.addProperty(key, bool.value());
			return;
		}
		if (setting instanceof ChoiceSetting choice) {
			json.addProperty(key, choice.option());
			return;
		}
		if (setting instanceof SliderSetting slider) {
			json.addProperty(key, slider.get());
			return;
		}
		if (setting instanceof StringSetting text) {
			json.addProperty(key, text.value());
			return;
		}
		if (setting instanceof BlockListSetting blocks) {
			json.add(key, array(blocks.entries()));
			return;
		}
		if (setting instanceof WatchlistSetting watch) {
			json.add(key, array(watch.entries()));
		}
	}

	private static boolean hasArray(JsonObject json, String key) {
		if (json == null) {
			return false;
		}
		JsonElement element = json.get(key);
		return element != null && element.isJsonArray();
	}

	private static JsonArray array(List<String> values) {
		JsonArray out = new JsonArray();
		if (values != null) {
			for (String value : values) {
				out.add(value);
			}
		}
		return out;
	}

	private static boolean fallbackBool(BooleanSetting setting) {
		Boolean value = setting.get();
		return value != null && value;
	}

	private static int fallbackInt(SliderSetting setting) {
		Integer value = setting.get();
		return value == null ? setting.min() : value;
	}

	private static String fallbackString(StringSetting setting) {
		return setting.value();
	}
}
