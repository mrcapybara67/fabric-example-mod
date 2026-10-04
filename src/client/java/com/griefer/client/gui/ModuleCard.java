package com.griefer.client.gui;

import com.griefer.client.module.Module;
import com.griefer.client.module.Setting;
import com.griefer.client.module.SliderSetting;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Module card: a floating pane of glass holding the module name, description,
 * settings chevron and glass toggle. Expands smoothly to reveal slider rows
 * inside an inset glass well.
 *
 * When the module is enabled the card lights up: an accent bloom behind it, an
 * accent-tinted body, and a thin accent bar down the leading edge — the same
 * "this thing is active" language used by the sidebar, so the two read as one
 * system.
 *
 * Interaction contract (unchanged from the working version):
 *   - Click card body / toggle -> toggle the module
 *   - Click the chevron strip  -> expand/collapse settings (if any)
 *   - Click/drag a slider row  -> set the value (never toggles)
 */
public class ModuleCard {
	private static final int CHEVRON_W = 16;
	private static final int ROW_H = 34;
	private static final int DESC_H = 12;
	private static final int PAD = 8;

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

		float hv = hover.value();
		float onv = on.value();
		int r = UiTheme.RADIUS;

		// Enabled: the card is lit from inside.
		if (onv > 0.02f) {
			Ui.roundRect(g, x - 2, y - 2, w + 4, h + 4, r + 2,
					Ui.lerpColor(0x00000000, UiTheme.GLOW_ACCENT, onv * 0.4f));
		}
		Ui.roundRect(g, x + 2, y + 3, w - 4, h, r, 0x38000000);

		// Body: glass, lifting slightly on hover and warming with the accent.
		int top = Ui.lerpColor(UiTheme.CARD_TOP, UiTheme.CARD_HOVER_TOP, hv);
		int bottom = Ui.lerpColor(UiTheme.CARD_BOTTOM, UiTheme.CARD_HOVER_BOTTOM, hv);
		if (pressed) {
			top = Ui.lerpColor(top, 0xFF141C27, 0.45f);
			bottom = Ui.lerpColor(bottom, 0xFF0E141D, 0.45f);
		}
		Ui.glassFill(g, x, y, w, h, r, top, bottom);
		if (onv > 0.02f) {
			Ui.glassFill(g, x, y, w, h, r,
					Ui.withAlpha(UiTheme.ACCENT, Math.round(26 * onv)),
					Ui.withAlpha(UiTheme.ACCENT, Math.round(8 * onv)));
		}
		Ui.glassEdge(g, x, y, w, h, r,
				Ui.lerpColor(UiTheme.EDGE_TOP, 0xA6FFFFFF, hv * 0.5f + onv * 0.5f),
				Ui.lerpColor(UiTheme.EDGE_BOTTOM, 0x30FFFFFF, hv));
		Ui.innerBevel(g, x, y, w, h, r,
				Ui.lerpColor(UiTheme.INNER_TOP, 0x40FFFFFF, onv), UiTheme.INNER_BOTTOM);

		// Leading accent bar while enabled.
		if (onv > 0.02f) {
			int barH = Math.max(6, Math.round((h - 16) * onv));
			int barY = y + (h - barH) / 2;
			Ui.glassFill(g, x + 1, barY, 3, barH, 2,
					Ui.withAlpha(UiTheme.ACCENT, Math.round(255 * onv)),
					Ui.withAlpha(UiTheme.ACCENT_DIM, Math.round(255 * onv)));
		}

		// Name + description, two-line hierarchy
		int textX = x + UiTheme.SP_4 + 2;
		int nameY = y + 11;
		int textRight = x + w - UiTheme.SP_3 - UiTheme.TOGGLE_W - UiTheme.SP_3;
		if (hasSettings()) {
			textRight -= CHEVRON_W;
		}
		int nameColor = Ui.lerpColor(UiTheme.TEXT_2, UiTheme.TEXT, Math.max(onv, hv * 0.7f));
		Ui.drawEllipsized(g, UiFonts.medium(), title, textX, nameY, textRight - textX, nameColor);

		String desc = description;
		if (showCategory) {
			String tag = module.category().title;
			desc = desc == null ? tag : desc + "  ·  " + tag;
		}
		if (desc != null) {
			Ui.drawEllipsized(g, UiFonts.regular(), desc, textX, nameY + DESC_H, textRight - textX, UiTheme.TEXT_3);
		}

		// Settings chevron (small, left of the toggle)
		if (hasSettings()) {
			int cx = x + w - UiTheme.SP_3 - UiTheme.TOGGLE_W - UiTheme.SP_3 - CHEVRON_W + 2;
			int cy = y + UiTheme.CARD_H / 2 - 6;
			int chev = Ui.lerpColor(UiTheme.TEXT_3, UiTheme.TEXT, Math.max(hv, expand.value() * 0.6f));
			UiIcons.chevron(g, cx, cy, 12, chev, expand.value());
		}

		// Glass toggle
		int tx = x + w - UiTheme.SP_3 - UiTheme.TOGGLE_W;
		int ty = y + (UiTheme.CARD_H - UiTheme.TOGGLE_H) / 2;
		toggle.update(module.isEnabled(), hovered, delta);
		toggle.render(g, tx, ty, UiTheme.TOGGLE_W, UiTheme.TOGGLE_H);

		// Settings well: an inset glass panel holding the slider rows.
		int full = settingsFullHeight();
		if (full > 0 && expand.value() > 0.01f) {
			int clipH = Math.round(full * expand.value());
			if (Ui.beginClip(g, x + 1, y + UiTheme.CARD_H, w - 2, clipH)) {
				int wy = y + UiTheme.CARD_H;
				Ui.glassFill(g, x + UiTheme.SP_3, wy, w - UiTheme.SP_3 * 2, full - 2, UiTheme.RADIUS,
						0x8C0B1017, 0xA6090D13);
				Ui.glassEdge(g, x + UiTheme.SP_3, wy, w - UiTheme.SP_3 * 2, full - 2, UiTheme.RADIUS,
						0x1FFFFFFF, 0x0DFFFFFF);

				int cy = wy + PAD;
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
		int trackX0 = x + UiTheme.SP_4 + 4;
		int trackX1 = x + w - UiTheme.SP_4 - 4;
		int trackY = rowY + 24;
		ui.bind(sliderLabels.get(slider), String.valueOf(slider.get()), trackX0, trackX1, trackY);
		ui.render(g, UiFonts.regular(), trackX0, trackX1 - trackX0);
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
		int cx = x + w - UiTheme.SP_3 - UiTheme.TOGGLE_W - UiTheme.SP_3 - CHEVRON_W;
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
