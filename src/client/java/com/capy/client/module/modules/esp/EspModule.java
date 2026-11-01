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
 *   <li><b>colour</b>: an ARGB value per overlay, or - for an overlay that
 *       draws several kinds of thing - one per kind, reached through
 *       {@link #colorFor(AABB)}. Its alpha is the outline strength; the fill
 *       is derived from it, so a single control drives the whole look without
 *       a second "opacity" setting;</li>
 *   <li><b>range</b>: the hard distance limit. It is also what the scanners
 *       use to decide how far to look, so pulling it in genuinely reduces the
 *       work instead of just hiding the result;</li>
 *   <li><b>fill / outline</b>: either layer can be turned off. Outlines alone
 *       are the cheapest and the most readable over terrain;</li>
 *   <li><b>tracers</b>: a line from the centre of the crosshair to the centre
 *       of every box, drawn with the same colour and the same wall rule as the
 *       box. Every tracer meets at one point on screen, which is what makes a
 *       bundle of them readable as a direction instead of as a fan.</li>
 * </ul>
 *
 * Every overlay draws through walls - there is no setting for it. A highlight
 * that a wall can hide is not much of an overlay, and the one switch it used to
 * have was one more row to toggle for the same result.
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

	/** The overlay's own colour, or null when the subclass owns several. */
	protected final ColorSetting color;
	protected final SliderSetting range;
	protected final BooleanSetting fill;
	protected final BooleanSetting outline;
	protected final BooleanSetting tracers;
	private final int defaultColor;
	private final int defaultRange;
	private final boolean defaultTracers;

	protected EspModule(String id, int defaultColor, int defaultRange, boolean defaultTracers) {
		this(id, Integer.valueOf(defaultColor), defaultRange, defaultTracers);
	}

	/**
	 * An overlay whose colours belong to the things it draws rather than to
	 * the overlay: storage highlights every container with the colour of the
	 * kind it is, so it adds a row per kind instead of one shared row.
	 */
	protected EspModule(String id, int defaultRange, boolean defaultTracers) {
		this(id, null, defaultRange, defaultTracers);
	}

	private EspModule(String id, Integer defaultColor, int defaultRange, boolean defaultTracers) {
		// Every ESP overlay lives in DonutSMP: it is the category players open
		// on that server, and grouping the three overlays together makes their
		// shared colour/range/tracer controls easy to compare.
		this(Category.DONUT_SMP, id, defaultColor, defaultRange, defaultTracers);
	}

	/**
	 * An overlay that belongs in a category of its own.
	 *
	 * A finder searches the world rather than marking a server, so it sits with
	 * the other finders; everything else - the colour, the range, the fill and
	 * the tracers - is the shared overlay behaviour and comes from here.
	 */
	protected EspModule(Category category, String id, int defaultColor, int defaultRange, boolean defaultTracers) {
		this(category, id, Integer.valueOf(defaultColor), defaultRange, defaultTracers);
	}

	private EspModule(Category category, String id, Integer defaultColor, int defaultRange, boolean defaultTracers) {
		super(category, id);
		this.defaultColor = defaultColor == null ? 0 : defaultColor;
		this.defaultRange = defaultRange;
		this.defaultTracers = defaultTracers;
		// The colour is the first row: it is the setting people open the card
		// for, and it is the only one whose effect is visible immediately.
		this.color = defaultColor == null ? null : setting(new ColorSetting("color", defaultColor));
		this.range = setting(new SliderSetting("range", defaultRange, MIN_RANGE, MAX_RANGE));
		this.fill = setting(new BooleanSetting("fill", true));
		this.outline = setting(new BooleanSetting("outline", true));
		this.tracers = setting(new BooleanSetting("tracers", defaultTracers));
	}

	/** The boxes to draw this frame. Never allocates a new list per call. */
	public abstract List<AABB> boxes();

	/**
	 * The colour of the overlay as a whole.
	 *
	 * An overlay that owns several colours returns its representative one here:
	 * the card's accent bar and anything else that only has room for one value
	 * still gets a colour that means this overlay.
	 */
	public int color() {
		return color == null ? 0xFFFFFFFF : color.argb();
	}

	/**
	 * The colour of one box.
	 *
	 * The default is the overlay's own colour; an overlay that draws more than
	 * one kind of thing overrides this so a chest and a shulker box do not have
	 * to share a hue.
	 */
	public int colorFor(AABB box) {
		return color();
	}

	/**
	 * The fill colour of a box: the given hue at a fraction of its alpha.
	 * Deriving it keeps the two layers in step — dragging the colour's alpha
	 * down makes the whole highlight subtler instead of leaving a solid block
	 * behind a faint edge. The top of a face is slightly stronger than its
	 * bottom, which is what stops a flat translucent box from looking like a
	 * glitchy tinted window.
	 */
	public static int fillTop(int argb) {
		int alpha = Math.round(((argb >>> 24) & 0xFF) * 0.22f);
		return (alpha << 24) | (argb & 0xFFFFFF);
	}

	public static int fillBottom(int argb) {
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

	/** True when a tracer line runs from the crosshair to every box centre. */
	public boolean tracers() {
		return tracers.value();
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
		if (color != null) {
			color.set(ConfigValues.intValue(json, "color", defaultColor));
		}
		range.set(ConfigValues.intValue(json, "range", defaultRange));
		fill.set(ConfigValues.boolValue(json, "fill", true));
		outline.set(ConfigValues.boolValue(json, "outline", true));
		tracers.set(ConfigValues.boolValue(json, "tracers", defaultTracers));
	}

	@Override
	public void saveConfig(JsonObject json) {
		if (color != null) {
			json.addProperty("color", color.argb());
		}
		json.addProperty("range", range());
		json.addProperty("fill", fills());
		json.addProperty("outline", outlines());
		json.addProperty("tracers", tracers());
	}
}
