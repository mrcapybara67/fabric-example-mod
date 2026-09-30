package com.griefer.client.gui;

import com.griefer.client.module.Module;
import com.griefer.client.module.Setting;
import com.griefer.client.module.SliderSetting;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Modern module card: name (medium weight), description (regular weight),
 * settings chevron, pill toggle. Expands smoothly to reveal slider rows.
 *
 * Interaction contract (unchanged from the working version):
 *   - Click card body / toggle -> toggle the module
 *   - Click the chevron strip  -> expand/collapse settings (if any)
 *   - Click/drag a slider row  -> set the value (never toggles)
 */
public class ModuleCard {
	private static final int CHEVRON_W = 14;
	private static final int ROW_H = 26;
	private static final int DESC_H = 12;
	private static final int PAD = 6;

	private final Module module;
	private final UiToggle toggle = new UiToggle();
	private final Map<SliderSetting, UiSlider> sliders = new IdentityHashMap<>();

	private final Anim hover = new Anim(0f, 14f);
	private final Anim on = new Anim(0f, 18f);
	private final Anim expand = new Anim(0f, 16f);
	private boolean expanded;
	private boolean pressed;

	// Display strings are static per module — resolved once, not per frame.
	private final String title;
	private final String description;
	private final Map<SliderSetting, String> sliderLabels = new IdentityHashMap<>();

	// Geometry of the most recent frame (for hit-testing)
	private int x;
	private int y;
	private int w;
	private int h;
	private int animatedHeight;

	public ModuleCard(Module module) {
		this.module = module;
		this.title = resolveOrFallback(module.translationKey(), module.id());
		this.description = resolveOrFallback(module.descriptionKey(), null);
		for (Setting<?> s : module.settings()) {
			if (s instanceof SliderSetting slider) {
				UiSlider ui = new UiSlider();
				ui.bindModel(new FixedModel(slider));
				sliders.put(slider, ui);
				sliderLabels.put(slider, sliderLabel(slider));
			}
		}
	}

	private static String resolveOrFallback(String key, String fallback) {
		String s = net.minecraft.network.chat.Component.translatable(key).getString();
		return s.equals(key) ? fallback : s;
	}

	/** Static per-slider model, created once (never per frame). */
	private static final class FixedModel implements UiSlider.SliderModel {
		private final SliderSetting slider;

		FixedModel(SliderSetting slider) {
			this.slider = slider;
		}

		@Override
		public double fraction() {
			return slider.fraction();
		}

		@Override
		public void setFraction(double fraction) {
			slider.setFromFraction(fraction);
		}
	}

	public Module module() {
		return module;
	}

	/** Cached display title (resolved once at construction). */
	public String title() {
		return title;
	}

	/** Cached description text; empty string when the module has none. */
	public String descriptionText() {
		return description == null ? "" : description;
	}

	/** Full height with settings fully expanded. */
	public int fullHeight() {
		return UiTheme.CARD_H + settingsFullHeight();
	}

	/** Advances the expand animation once per frame and caches the height. */
	public void animate(float delta) {
		expand.to(expanded ? 1f : 0f, delta);
		animatedHeight = UiTheme.CARD_H + Math.round(settingsFullHeight() * expand.value());
	}

	/** Height for the current frame (after {@link #animate}). */
	public int height() {
		return animatedHeight;
	}

	private int settingsFullHeight() {
		if (sliders.isEmpty()) {
			return 0;
		}
		// Must stay in sync with sliderAt(): sliders start at CARD_H + PAD.
		return PAD + sliders.size() * ROW_H;
	}

	// ---------------------------------------------------------------- render

	public void render(GuiGraphics g, int x, int y, int w, int mouseX, int mouseY, float delta, boolean showCategory) {
		this.x = x;
		this.y = y;
		this.w = w;
		int h = animatedHeight;
		this.h = h;

		boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		hover.to(hovered ? 1f : 0f, delta);
		on.to(module.isEnabled() ? 1f : 0f, delta);

		// Elevation: base -> hover; enabled adds the faintest accent tint
		int bg = Ui.lerpColor(UiTheme.SURFACE_2, UiTheme.SURFACE_3, hover.value() * 0.85f);
		if (pressed) {
			bg = Ui.lerpColor(bg, 0xFF2A3340, 0.6f);
		}
		if (on.value() > 0.5f) {
			bg = Ui.lerpColor(bg, UiTheme.ACCENT_10, 0.35f);
		}
		Ui.roundRect(g, x, y, w, h, UiTheme.RADIUS, bg);

		// Name + description, two-line hierarchy
		int textX = x + UiTheme.SP_4;
		int nameY = y + UiTheme.SP_3 - 1;
		int textRight = x + w - UiTheme.SP_3 - UiTheme.TOGGLE_W - UiTheme.SP_3;
		if (hasSettings()) {
			textRight -= CHEVRON_W;
		}
		int nameColor = Ui.lerpColor(UiTheme.TEXT_2, UiTheme.TEXT, Math.max(on.value(), hover.value() * 0.7f));
		Ui.drawEllipsized(g, UiFonts.medium(), title, textX, nameY, textRight - textX, nameColor);

		String desc = description;
		if (showCategory) {
			String tag = module.category().title;
			desc = desc == null ? tag : desc + "  ·  " + tag;
		}
		if (desc != null) {
			Ui.drawEllipsized(g, UiFonts.regular(), desc, textX, nameY + 11, textRight - textX, UiTheme.TEXT_3);
		}

		// Settings chevron (small, left of the toggle)
		if (hasSettings()) {
			int cx = x + w - UiTheme.SP_3 - UiTheme.TOGGLE_W - UiTheme.SP_3;
			int cy = y + UiTheme.CARD_H / 2 - 1;
			boolean openDir = expand.value() > 0.5f;
			int chev = Ui.lerpColor(UiTheme.TEXT_3, UiTheme.TEXT, hover.value());
			for (int i = 0; i <= 1; i++) {
				int row = openDir ? 1 - i : i;
				g.fill(cx + i, cy + row, cx + i + 1, cy + row + 1, chev);
				g.fill(cx + 3 - i, cy + row, cx + 4 - i, cy + row + 1, chev);
			}
		}

		// Pill toggle
		int tx = x + w - UiTheme.SP_3 - UiTheme.TOGGLE_W;
		int ty = y + (UiTheme.CARD_H - UiTheme.TOGGLE_H) / 2;
		toggle.update(module.isEnabled(), hovered, delta);
		toggle.render(g, tx, ty, UiTheme.TOGGLE_W, UiTheme.TOGGLE_H);

		// Settings section, clipped by animated height
		int full = settingsFullHeight();
		if (full > 0 && expand.value() > 0.01f) {
			int clipH = Math.round(full * expand.value());
			if (Ui.beginClip(g, x, y + UiTheme.CARD_H, w, clipH)) {
				int cy = y + UiTheme.CARD_H + PAD;
				for (Setting<?> s : module.settings()) {
					if (s instanceof SliderSetting slider) {
						renderSliderRow(g, sliders.get(slider), slider, x, w, cy);
						cy += ROW_H;
					}
				}
				Ui.endClip(g);
			}
		}
	}

	private void renderSliderRow(GuiGraphics g, UiSlider ui, SliderSetting slider, int x, int w, int rowY) {
		int trackX0 = x + UiTheme.SP_4;
		int trackX1 = x + w - UiTheme.SP_4;
		int trackY = rowY + 17;
		ui.bind(sliderLabels.get(slider), String.valueOf(slider.get()), trackX0, trackX1, trackY);
		ui.render(g, UiFonts.regular(), x + UiTheme.SP_4, w - UiTheme.SP_4 * 2);
	}

	private String sliderLabel(SliderSetting slider) {
		String key = "griefer.module." + module.id() + "." + slider.key();
		String s = net.minecraft.network.chat.Component.translatable(key).getString();
		if (s.equals(key)) {
			s = Character.toUpperCase(slider.key().charAt(0)) + slider.key().substring(1);
		}
		return s;
	}

	public boolean hasSettings() {
		return !sliders.isEmpty();
	}

	// ---------------------------------------------------------------- input

	/** Full current bounds (card + any expanded settings). */
	public boolean contains(double mx, double my) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	/** Returns true if the press was consumed by this card. */
	public boolean mouseClicked(double mx, double my, int button) {
		if (button != 0 || !contains(mx, my)) {
			return false;
		}

		// Slider rows consume first so they can never toggle the module.
		if (expanded) {
			UiSlider slider = sliderAt(my);
			if (slider != null) {
				slider.pressed(mx);
				return true;
			}
		}

		// Chevron strip expands/collapses settings.
		if (overChevron(mx, my)) {
			expanded = !expanded;
			return true;
		}

		// Everything else (toggle, name, body) toggles the module.
		pressed = true;
		module.toggle();
		return true;
	}

	/** Returns true if the drag was consumed. */
	public boolean mouseDragged(double mx, double my) {
		for (UiSlider slider : sliders.values()) {
			slider.dragged(mx);
		}
		return false;
	}

	/** Returns true if a card interaction finished (click or drag end). */
	public boolean mouseReleased(double mx, double my) {
		boolean hadPress = pressed;
		for (UiSlider slider : sliders.values()) {
			slider.released();
		}
		pressed = false;
		return hadPress;
	}

	private boolean overChevron(double mx, double my) {
		if (!hasSettings()) {
			return false;
		}
		int cx = x + w - UiTheme.SP_3 - UiTheme.TOGGLE_W - UiTheme.SP_3;
		return mx >= cx - 2 && mx < cx + CHEVRON_W && my >= y && my < y + UiTheme.CARD_H;
	}

	private UiSlider sliderAt(double my) {
		if (!expanded) {
			return null;
		}
		int cy = y + UiTheme.CARD_H + PAD;
		for (Setting<?> s : module.settings()) {
			if (s instanceof SliderSetting slider) {
				if (my >= cy && my < cy + ROW_H) {
					return sliders.get(slider);
				}
				cy += ROW_H;
			}
		}
		return null;
	}
}
