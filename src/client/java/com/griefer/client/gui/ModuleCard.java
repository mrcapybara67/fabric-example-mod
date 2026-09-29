package com.griefer.client.gui;

import com.griefer.client.module.Module;
import com.griefer.client.module.Setting;
import com.griefer.client.module.SliderSetting;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Reusable card representing one module: name, optional description, an
 * animated toggle, and (for modules that have them) expandable settings rows.
 *
 * Interaction contract:
 *   - Click anywhere on the card body -> toggle the module
 *   - Click the chevron strip -> expand/collapse settings (if any)
 *   - Click/drag a slider row -> set the value (never toggles)
 *
 * The card never mutates anything except its own module's public API
 * ({@link Module#toggle()}, {@link SliderSetting#setFromFraction}).
 */
public class ModuleCard {
	private static final int CHEVRON_W = 16;
	private static final int ROW_H = 26;
	private static final int DESC_H = 12;
	private static final int SETTINGS_PAD = 8;

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
		String s = Component.translatable(key).getString();
		return s.equals(key) ? fallback : s;
	}

	/** Per-slider model, created once (never per frame). */
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
		int h = DESC_H + SETTINGS_PAD;
		if (descriptionText() == null) {
			h -= DESC_H;
		}
		return h + sliders.size() * ROW_H;
	}

	private String descriptionText() {
		return description;
	}

	// ---------------------------------------------------------------- render

	public void render(GuiGraphics g, Font font, int x, int y, int w, int mouseX, int mouseY, float delta) {
		this.x = x;
		this.y = y;
		this.w = w;
		int h = animatedHeight;
		this.h = h;

		boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		hover.to(hovered ? 1f : 0f, delta);
		on.to(module.isEnabled() ? 1f : 0f, delta);

		int bg = Ui.lerpColor(
				Ui.lerpColor(UiTheme.SURFACE_1, UiTheme.SURFACE_2, hover.value()),
				UiTheme.SURFACE_2, 0.35f * on.value());
		if (pressed) {
			bg = Ui.lerpColor(bg, UiTheme.SURFACE_3, 0.6f);
		}
		Ui.roundRect(g, x, y, w, h, UiTheme.RADIUS, bg);

		// Enabled: accent bar down the left edge
		if (on.value() > 0.02f) {
			int barH = Math.round((h - 6) * Math.min(1f, on.value() * 1.4f));
			Ui.roundRect(g, x, y + (h - barH) / 2, 2, barH, 1, UiTheme.ACCENT);
		}

		// Name
		int nameRight = x + w - UiTheme.SP_4 - UiTheme.TOGGLE_W - UiTheme.SP_3;
		if (hasSettings()) {
			nameRight -= CHEVRON_W;
		}
		int nameColor = Ui.lerpColor(UiTheme.TEXT_SUB, UiTheme.TEXT, Math.max(on.value(), hover.value() * 0.7f));
		Ui.drawEllipsized(g, font, moduleTitle(), x + UiTheme.SP_5, y + 9, nameRight - (x + UiTheme.SP_5), nameColor);

		// Chevron (modules with settings)
		if (hasSettings()) {
			int cx = x + w - UiTheme.SP_4 - UiTheme.TOGGLE_W - UiTheme.SP_3 + 2;
			int cy = y + UiTheme.CARD_H / 2 - 2;
			boolean openDir = expand.value() > 0.5f;
			for (int i = 0; i <= 2; i++) {
				int row = openDir ? 2 - i : i;
				g.fill(cx - i, cy + row, cx + i + 1, cy + row + 1, UiTheme.TEXT_FAINT);
			}
		}

		// Toggle
		int tx = x + w - UiTheme.SP_4 - UiTheme.TOGGLE_W;
		int ty = y + (UiTheme.CARD_H - UiTheme.TOGGLE_H) / 2;
		toggle.update(module.isEnabled(), hovered, delta);
		toggle.render(g, tx, ty, UiTheme.TOGGLE_W, UiTheme.TOGGLE_H);

		// Settings section, clipped by animated height
		int full = settingsFullHeight();
		if (full > 0 && expand.value() > 0.01f) {
			int clipH = Math.round(full * expand.value());
			if (Ui.beginClip(g, x, y + UiTheme.CARD_H, w, clipH)) {
				int cy = y + UiTheme.CARD_H;
				String desc = descriptionText();
				if (desc != null) {
					Ui.drawEllipsized(g, font, desc, x + UiTheme.SP_5, cy, w - UiTheme.SP_5 * 2, UiTheme.TEXT_FAINT);
					cy += DESC_H;
				}
				for (Setting<?> s : module.settings()) {
					if (s instanceof SliderSetting slider) {
						renderSliderRow(g, font, sliders.get(slider), slider, x, w, cy);
						cy += ROW_H;
					}
				}
				Ui.endClip(g);
			}
		}
	}

	private void renderSliderRow(GuiGraphics g, Font font, UiSlider ui, SliderSetting slider, int x, int w, int rowY) {
		int trackX0 = x + UiTheme.SP_5;
		int trackX1 = x + w - UiTheme.SP_5;
		int trackY = rowY + 17;
		ui.bind(sliderLabels.get(slider), String.valueOf(slider.get()), trackX0, trackX1, trackY);
		ui.render(g, font, x + UiTheme.SP_5, w - UiTheme.SP_5 * 2);
	}

	private String moduleTitle() {
		return title;
	}

	private String sliderLabel(SliderSetting slider) {
		String key = "griefer.module." + module.id() + "." + slider.key();
		String s = Component.translatable(key).getString();
		if (s.equals(key)) {
			s = Character.toUpperCase(slider.key().charAt(0)) + slider.key().substring(1);
		}
		return s;
	}

	public boolean hasSettings() {
		return !sliders.isEmpty();
	}

	// ---------------------------------------------------------------- input

	private boolean overChevron(double mx, double my) {
		if (!hasSettings()) {
			return false;
		}
		int cx = x + w - UiTheme.SP_4 - UiTheme.TOGGLE_W - UiTheme.SP_3;
		return mx >= cx - 2 && mx < cx + CHEVRON_W && my >= y && my < y + UiTheme.CARD_H;
	}

	private UiSlider sliderAt(double my) {
		if (!expanded) {
			return null;
		}
		int cy = y + UiTheme.CARD_H;
		String desc = descriptionText();
		if (desc != null) {
			cy += DESC_H;
		}
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
}
