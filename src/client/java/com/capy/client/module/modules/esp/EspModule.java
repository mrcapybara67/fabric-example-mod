package com.capy.client.module.modules.esp;

import com.capy.client.config.ConfigValues;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.Category;
import com.capy.client.module.ColorSetting;
import com.capy.client.module.Configurable;
import com.capy.client.module.Module;
import com.capy.client.module.SliderSetting;
import com.capy.client.module.Tickable;
import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;

/**
 * Shared base for the three world overlays: player, storage and block ESP.
 *
 * All three answer the same two questions — <em>what do I highlight</em> and
 * <em>how is it drawn</em> — so the answers live here and the subclasses only
 * supply the boxes:
 *
 * <ul>
 *   <li><b>colour</b>: one ARGB value per module. Its alpha is the outline
 *       strength; the fill is derived from it, so a single control drives the
 *       whole look without a second "opacity" setting;</li>
 *   <li><b>range</b>: the hard distance limit. It is also what the scanners
 *       use to decide how far to look, so pulling it in genuinely reduces the
 *       work instead of just hiding the result;</li>
 *   <li><b>fill / outline</b>: either layer can be turned off. Outlines alone
 *       are the cheapest and the most readable over terrain.</li>
 * </ul>
 *
 * A module only keeps a list of boxes that it refreshes on its own schedule.
 * The renderer never asks a module to scan; it only reads the list, which keeps
 * the cost of a frame independent of the cost of a scan.
 */
public abstract class EspModule extends Module implements Configurable, Tickable {
	public static final int MIN_RANGE = 8;
	public static final int MAX_RANGE = 128;
	/** Upper bound on the boxes a single module contributes to a frame. */
	public static final int MAX_BOXES = 512;

	protected final ColorSetting color;
	protected final SliderSetting range;
	protected final BooleanSetting fill;
	protected final BooleanSetting outline;
	protected final BooleanSetting throughWalls;
	private final int defaultColor;
	private final int defaultRange;

	protected EspModule(String id, int defaultColor, int defaultRange) {
		super(Category.VISUAL, id);
		this.defaultColor = defaultColor;
		this.defaultRange = defaultRange;
		// The colour is the first row: it is the setting people open the card
		// for, and it is the only one whose effect is visible immediately.
		this.color = setting(new ColorSetting("color", defaultColor));
		this.range = setting(new SliderSetting("range", defaultRange, MIN_RANGE, MAX_RANGE));
		this.fill = setting(new BooleanSetting("fill", true));
		this.outline = setting(new BooleanSetting("outline", true));
		// On by default: a highlight that a wall can hide is not much of an
		// overlay. Turning it off restores normal occlusion for players who
		// prefer the boxes to sit in the scene instead of on top of it.
		this.throughWalls = setting(new BooleanSetting("through_walls", true));
	}

	/** The boxes to draw this frame. Never allocates a new list per call. */
	public abstract List<AABB> boxes();

	public int color() {
		return color.argb();
	}

	/**
	 * The fill colour of a box: the module hue at a fraction of the outline
	 * alpha. Deriving it keeps the two layers in step — dragging the colour's
	 * alpha down makes the whole highlight subtler instead of leaving a solid
	 * block behind a faint edge. The top of a face is slightly stronger than
	 * its bottom, which is what stops a flat translucent box from looking like
	 * a glitchy tinted window.
	 */
	public int fillTop() {
		int argb = color.argb();
		int alpha = Math.round(((argb >>> 24) & 0xFF) * 0.22f);
		return (alpha << 24) | (argb & 0xFFFFFF);
	}

	public int fillBottom() {
		int argb = color.argb();
		int alpha = Math.round(((argb >>> 24) & 0xFF) * 0.08f);
		return (alpha << 24) | (argb & 0xFFFFFF);
	}

	public int range() {
		return range.get();
	}

	public boolean fills() {
		return fill.value();
	}

	public boolean outlines() {
		return outline.value();
	}

	/** True when the overlay is drawn without a depth test (seen through walls). */
	public boolean throughWalls() {
		return throughWalls.value();
	}

	/**
	 * Modules that highlight the world refresh their cache on this hook. The
	 * player overlay answers every frame instead and simply inherits an empty
	 * tick.
	 */
	@Override
	public void onTick(Minecraft mc) {
	}

	/** Called when the module is switched off, so nothing stale stays cached. */
	protected void clear() {
	}

	@Override
	protected void onToggle(boolean state) {
		if (!state) {
			clear();
		}
	}

	// ---------------------------------------------------------- persistence

	@Override
	public void loadConfig(JsonObject json) {
		color.set(ConfigValues.intValue(json, "color", defaultColor));
		range.set(ConfigValues.intValue(json, "range", defaultRange));
		fill.set(ConfigValues.boolValue(json, "fill", true));
		outline.set(ConfigValues.boolValue(json, "outline", true));
		throughWalls.set(ConfigValues.boolValue(json, "through_walls", true));
	}

	@Override
	public void saveConfig(JsonObject json) {
		json.addProperty("color", color.argb());
		json.addProperty("range", range());
		json.addProperty("fill", fills());
		json.addProperty("outline", outlines());
		json.addProperty("through_walls", throughWalls());
	}
}
