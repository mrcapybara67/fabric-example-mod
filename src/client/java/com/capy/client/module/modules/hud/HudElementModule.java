package com.capy.client.module.modules.hud;

import com.capy.client.config.ConfigValues;
import com.capy.client.module.Category;
import com.capy.client.module.Configurable;
import com.capy.client.module.Module;
import com.capy.client.module.SliderSetting;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

/**
 * One element of the HUD: a single compact chip that shows one fact.
 *
 * The HUD is a set of independent modules rather than one panel, so every
 * readout can be switched on, off and placed on its own. A module only has to
 * say what its value is; where the chip goes and how it is drawn lives in
 * {@link HudRenderer}, so every element is guaranteed to look and align the
 * same way.
 *
 * Placement is a nine cell grid (see the {@code ANCHOR_*} constants) plus a
 * small pixel nudge, which is enough to build any common HUD layout without a
 * drag editor. Elements that share a cell are stacked in registration order,
 * so turning more of them on can never make two chips overlap.
 */
public abstract class HudElementModule extends Module implements Configurable {
	public static final int ANCHOR_TOP_LEFT = 0;
	public static final int ANCHOR_TOP_CENTER = 1;
	public static final int ANCHOR_TOP_RIGHT = 2;
	public static final int ANCHOR_MIDDLE_LEFT = 3;
	public static final int ANCHOR_MIDDLE_CENTER = 4;
	public static final int ANCHOR_MIDDLE_RIGHT = 5;
	public static final int ANCHOR_BOTTOM_LEFT = 6;
	public static final int ANCHOR_BOTTOM_CENTER = 7;
	public static final int ANCHOR_BOTTOM_RIGHT = 8;

	public static final int MAX_NUDGE = 80;

	private final String label;
	private final int defaultAnchor;
	private final SliderSetting anchor;
	private final SliderSetting offsetX;
	private final SliderSetting offsetY;

	protected HudElementModule(String id, String label, int defaultAnchor) {
		super(Category.HUD, id);
		this.label = label;
		this.defaultAnchor = clamp(defaultAnchor, ANCHOR_TOP_LEFT, ANCHOR_BOTTOM_RIGHT);
		this.anchor = setting(new SliderSetting("anchor", this.defaultAnchor,
				ANCHOR_TOP_LEFT, ANCHOR_BOTTOM_RIGHT));
		this.offsetX = setting(new SliderSetting("offset_x", 0, -MAX_NUDGE, MAX_NUDGE));
		this.offsetY = setting(new SliderSetting("offset_y", 0, -MAX_NUDGE, MAX_NUDGE));
	}

	/** The muted prefix drawn before the value; empty for a bare readout. */
	public String label() {
		return label;
	}

	/** The value to show. Called once per frame, so it must stay cheap. */
	public abstract String value(Minecraft mc);

	/** Which grid cell the chip is anchored to. */
	public int anchor() {
		return clamp(anchor.get(), ANCHOR_TOP_LEFT, ANCHOR_BOTTOM_RIGHT);
	}

	/** Horizontal nudge in pixels, applied after the anchor. */
	public int offsetX() {
		return offsetX.get();
	}

	/** Vertical nudge in pixels, applied after the anchor and the stacking. */
	public int offsetY() {
		return offsetY.get();
	}

	public int defaultAnchor() {
		return defaultAnchor;
	}

	// ---------------------------------------------------------- persistence

	@Override
	public void loadConfig(JsonObject json) {
		anchor.set(ConfigValues.intValue(json, "anchor", defaultAnchor));
		offsetX.set(ConfigValues.intValue(json, "offset_x", 0));
		offsetY.set(ConfigValues.intValue(json, "offset_y", 0));
	}

	@Override
	public void saveConfig(JsonObject json) {
		json.addProperty("anchor", anchor());
		json.addProperty("offset_x", offsetX());
		json.addProperty("offset_y", offsetY());
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
