package com.capy.client.module.modules.hud;

import com.capy.client.config.ConfigValues;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.Category;
import com.capy.client.module.ColorSetting;
import com.capy.client.module.Configurable;
import com.capy.client.module.Module;
import com.capy.client.module.SliderSetting;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

/**
 * One element of the HUD: a single compact chip that shows one fact.
 *
 * <p>The HUD is a set of independent modules rather than one panel, so every
 * readout can be switched on, off and placed on its own. A module only has to
 * say what its value is; where the chip goes and how it is drawn lives in
 * {@link HudRenderer}, so every element is guaranteed to look and align the
 * same way.</p>
 *
 * <h2>Moving a chip</h2>
 * Placement is a nine cell grid (the {@code ANCHOR_*} constants) plus a pixel
 * nudge, which is enough to build any common layout without a drag editor and
 * without a screen of its own:
 *
 * <ul>
 *   <li>{@code anchor} picks the cell and the stacking direction;</li>
 *   <li>{@code offset_x} / {@code offset_y} move the chip from that cell, up to
 *       {@value #MAX_NUDGE} pixels either way - enough to move a chip from one
 *       corner to the other on a 4K window, so the grid is a starting point
 *       rather than a cage;</li>
 *   <li>{@code background}, {@code accent}, {@code accent_bar} and
 *       {@code show_label} are the look of the chip itself.</li>
 * </ul>
 *
 * <p>Every setting is persisted per module, so a layout survives a restart, and
 * a module that is switched off keeps its position for the next time it is
 * switched on.</p>
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

	/**
	 * How far a chip may be nudged from its cell, in pixels.
	 *
	 * <p>Six hundred is more than half a 4K window at any GUI scale a player
	 * runs: at scale four the window is 960 by 540 logical pixels, at scale one
	 * it is 3840 by 2160, and half of the wider measurement is 1920 - which is
	 * why the drag editor re-anchors to the nearest cell before writing the
	 * nudge instead of relying on this range alone. Together, the grid and the
	 * nudge reach every position on the screen from the nearest cell.</p>
	 */
	public static final int MAX_NUDGE = 600;

	private final String label;
	private final int defaultAnchor;
	private final SliderSetting anchor;
	private final SliderSetting offsetX;
	private final SliderSetting offsetY;
	private final BooleanSetting showLabel;
	private final SliderSetting background;
	private final BooleanSetting accentBar;
	private final ColorSetting accent;

	protected HudElementModule(String id, String label, int defaultAnchor) {
		super(Category.HUD, id);
		this.label = label;
		this.defaultAnchor = clamp(defaultAnchor, ANCHOR_TOP_LEFT, ANCHOR_BOTTOM_RIGHT);
		this.anchor = setting(new SliderSetting("anchor", this.defaultAnchor,
				ANCHOR_TOP_LEFT, ANCHOR_BOTTOM_RIGHT));
		this.offsetX = setting(new SliderSetting("offset_x", 0, -MAX_NUDGE, MAX_NUDGE));
		this.offsetY = setting(new SliderSetting("offset_y", 0, -MAX_NUDGE, MAX_NUDGE));
		this.showLabel = setting(new BooleanSetting("show_label", true));
		// A plate at ninety five percent: the chip is a label on top of the
		// world, and the world has to stay readable through it. Zero draws the
		// value straight over the scene, which is the look some players want.
		this.background = setting(new SliderSetting("background", 95, 0, 100));
		this.accentBar = setting(new BooleanSetting("accent_bar", true));
		// Zero means "the interface accent", so a chip follows the theme until
		// the player gives it a colour of its own.
		this.accent = setting(new ColorSetting("accent", 0));
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

	/**
	 * Moves the chip to a nudge, clamped to the slider's range.
	 *
	 * <p>The drag editor writes through this rather than through the setting, so
	 * the clamp lives in one place: a drag that leaves the window would otherwise
	 * store a value the setting refuses, and the chip would snap back to where it
	 * was on the next frame while the pointer kept moving.</p>
	 */
	public void moveTo(int x, int y) {
		offsetX.set(clamp(x, -MAX_NUDGE, MAX_NUDGE));
		offsetY.set(clamp(y, -MAX_NUDGE, MAX_NUDGE));
	}

	/**
	 * Anchors the chip to a grid cell.
	 *
	 * <p>The drag editor writes through this while a chip is being moved: the
	 * nudge alone is capped at {@value #MAX_NUDGE} pixels, which is a sensible
	 * range for a setting but not for a drag that crosses the window. Choosing
	 * the nearest cell and nudging from <em>that</em> keeps every position on
	 * screen reachable, and the offset written back stays small.</p>
	 */
	public void setAnchor(int cell) {
		anchor.set(clamp(cell, ANCHOR_TOP_LEFT, ANCHOR_BOTTOM_RIGHT));
	}

	/** Puts the chip back on its cell. */
	public void resetPosition() {
		moveTo(0, 0);
	}

	public int defaultAnchor() {
		return defaultAnchor;
	}

	/** False draws the value alone, without the muted prefix. */
	public boolean labelVisible() {
		return showLabel.value();
	}

	/** Plate opacity, 0..255. */
	public int backgroundAlpha() {
		return Math.round(Math.max(0f, Math.min(1f, (float) background.real() / 100f)) * 255f);
	}

	/** False draws no accent bar, for a chip that should disappear into the HUD. */
	public boolean accentBarVisible() {
		return accentBar.value();
	}

	/** The chip's own accent, or 0 to follow the interface accent. */
	public int accentArgb() {
		int argb = accent.argb();
		return (argb >>> 24) == 0 ? 0 : argb;
	}

	// ---------------------------------------------------------- persistence

	@Override
	public void loadConfig(JsonObject json) {
		anchor.set(ConfigValues.intValue(json, "anchor", defaultAnchor));
		offsetX.set(ConfigValues.intValue(json, "offset_x", 0));
		offsetY.set(ConfigValues.intValue(json, "offset_y", 0));
		showLabel.set(ConfigValues.boolValue(json, "show_label", true));
		background.set(ConfigValues.intValue(json, "background", 95));
		accentBar.set(ConfigValues.boolValue(json, "accent_bar", true));
		accent.set(ConfigValues.intValue(json, "accent", 0));
	}

	@Override
	public void saveConfig(JsonObject json) {
		json.addProperty("anchor", anchor());
		json.addProperty("offset_x", offsetX());
		json.addProperty("offset_y", offsetY());
		json.addProperty("show_label", labelVisible());
		json.addProperty("background", Math.round(background.real()));
		json.addProperty("accent_bar", accentBarVisible());
		json.addProperty("accent", accentArgb());
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
