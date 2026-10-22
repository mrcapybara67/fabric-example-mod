package com.capy.client.gui;

import com.capy.client.module.BooleanSetting;
import com.capy.client.module.Module;
import com.capy.client.module.Setting;
import com.capy.client.module.SliderSetting;
import com.capy.client.module.WatchlistSetting;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Module card: a plum surface holding the module name, its description, a
 * settings indicator and the switch that enables it. Expands smoothly to
 * reveal its settings inside an inset well.
 *
 * When the module is enabled the card lights up: a soft accent bloom behind
 * it, a faintly warmer body and a thin accent bar down the leading edge —
 * the same "this thing is active" language the sidebar uses, so both read as
 * one system.
 *
 * Interaction contract:
 *   - Click card body / switch -> toggle the module
 *   - Click the chevron         -> expand/collapse the settings well
 *   - Click a setting control   -> drives that setting, never the module
 */
public class ModuleCard {
	/** Width of the clickable strip that holds the settings chevron. */
	private static final int CHEVRON_W = 16;
	/** Size of the chevron glyph itself, centred in that strip. */
	private static final int CHEVRON_SIZE = 12;
	/** Gap between the module name and its description. */
	private static final int DESC_GAP = 3;
	private static final int PAD = 10;

	private static final int ROW_SLIDER = 40;
	private static final int ROW_TOGGLE = 30;

	private final Module module;
	private final UiToggle toggle = new UiToggle();
	private final Map<SliderSetting, UiSlider> sliders = new IdentityHashMap<>();
	private final Map<SliderSetting, int[]> sliderBounds = new IdentityHashMap<>();
	private final Map<BooleanSetting, UiToggle> switches = new IdentityHashMap<>();
	private final Map<BooleanSetting, int[]> switchBounds = new IdentityHashMap<>();
	private final Map<WatchlistSetting, UiWatchlist> watchlists = new IdentityHashMap<>();

	private final Anim hover = new Anim(0f, 14f);
	private final Anim on = new Anim(0f, 18f);
	private final Anim expand = new Anim(0f, 16f);
	private boolean expanded;
	private boolean pressed;

	// Display strings are static per module — resolved once, not per frame.
	private final String title;
	private final String description;
	/** Description with the category appended, used while searchinc. */
	private final String taggedDescription;
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
		this.taggedDescription = description == null
				? module.category().title
				: description + "  ·  " + module.category().title;
		for (Setting<?> s : module.settings()) {
			if (s instanceof SliderSetting slider) {
				UiSlider ui = new UiSlider();
				ui.bindModel(new FixedModel(slider));
				sliders.put(slider, ui);
				sliderLabels.put(slider, settingLabel(slider.key()));
			} else if (s instanceof BooleanSetting bool) {
				switches.put(bool, new UiToggle());
			} else if (s instanceof WatchlistSetting watch) {
				watchlists.put(watch, new UiWatchlist(new WatchlistModel(watch), settingLabel(watch.key())));
			}
		}
	}

	/** Bridges the watchlist setting to the editor component. */
	private static final class WatchlistModel implements UiWatchlist.Model {
		private final WatchlistSetting setting;

		WatchlistModel(WatchlistSetting setting) {
			this.setting = setting;
		}

		@Override
		public java.util.List<String> entries() {
			return setting.entries();
		}

		@Override
		public WatchlistSetting.AddResult add(String name) {
			return setting.add(name);
		}

		@Override
		public boolean remove(String name) {
			return setting.remove(name);
		}
	}

	private static String resolveOrFallback(String key, String fallback) {
		String s = UiLabels.translate(key, null);
		return s == null ? fallback : s;
	}

	/** Resolves {@code capy.module.<module>.<setting>} with a readable fallback. */
	private String settingLabel(String settingKey) {
		String key = "capy.module." + module.id() + "." + settingKey;
		String s = UiLabels.translate(key, null);
		return s == null ? UiLabels.humanize(settingKey) : s;
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
		int total = 0;
		for (Setting<?> s : module.settings()) {
			total += settingHeight(s);
		}
		return total == 0 ? 0 : PAD + total;
	}

	private int settingHeight(Setting<?> s) {
		if (s instanceof SliderSetting) {
			return ROW_SLIDER;
		}
		if (s instanceof BooleanSetting) {
			return ROW_TOGGLE;
		}
		if (s instanceof WatchlistSetting watch) {
			UiWatchlist ui = watchlists.get(watch);
			return (ui == null ? 0 : ui.height()) + UiTheme.SP_3;
		}
		return 0;
	}

	/** Expands the settings well (used by the offline preview). */
	void setExpanded(boolean expanded) {
		this.expanded = expanded;
	}

	public boolean hasSettings() {
		return !module.settings().isEmpty();
	}

	// ---------------------------------------------------------------- render

	public void render(UiCanvas c, UiType type, int x, int y, int w, int mouseX, int mouseY, float delta,
			boolean showCategory) {
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

		// A soft shadow lifts the card off the workspace.
		Ui.roundRect(c, x + 1, y + 3, w - 2, h, r, 0x2E000000);

		// The body: a plum surface that brightens on hover and warms with
		// the accent while the module is enabled.
		int top = Ui.lerpColor(UiTheme.CARD_TOP, UiTheme.CARD_HOVER_TOP, hv);
		int bottom = Ui.lerpColor(UiTheme.CARD_BOTTOM, UiTheme.CARD_HOVER_BOTTOM, hv);
		if (pressed) {
			top = Ui.lerpColor(top, 0xFF191220, 0.45f);
			bottom = Ui.lerpColor(bottom, 0xFF140E19, 0.45f);
		}
		Ui.surface(c, x, y, w, h, r, top, bottom);
		if (onv > 0.02f) {
			Ui.surface(c, x, y, w, h, r,
					Ui.withAlpha(UiTheme.ACCENT, Math.round(22 * onv)),
					Ui.withAlpha(UiTheme.ACCENT_DEEP, Math.round(7 * onv)));
		}
		// A hairline, not a heavy frame.
		Ui.surfaceEdge(c, x, y, w, h, r,
				Ui.lerpColor(Ui.withAlpha(UiTheme.BORDER, 0xA8), Ui.withAlpha(UiTheme.ACCENT, 0xB0), onv * 0.8f),
				Ui.withAlpha(UiTheme.BORDER, 0x60));
		Ui.innerBevel(c, x, y, w, h, r, UiTheme.HIGHLIGHT, UiTheme.SHADE);

		// The leading accent bar marks the enabled state.
		if (onv > 0.02f) {
			int barH = Math.round((h - 22) * onv);
			int barY = y + (h - barH) / 2;
			Ui.surface(c, x + 1, barY, 3, barH, 2,
					Ui.withAlpha(UiTheme.ACCENT, Math.round(255 * onv)),
					Ui.withAlpha(UiTheme.ACCENT_DEEP, Math.round(255 * onv)));
		}

		// Name and description, aligned on one left edge. The two lines are
		// centred as one block in the card header, so a card with a
		// description and a card without both look deliberate instead of one
		// of them sitting high.
		UiText medium = type.medium();
		UiText regular = type.regular();
		int textX = x + UiTheme.SP_6;
		int textRight = hasSettings()
				? chevronLeft(w) - UiTheme.SP_3
				: x + w - UiTheme.SP_5 - UiTheme.TOGGLE_W - UiTheme.SP_4;
		String desc = showCategory ? taggedDescription : description;
		boolean hasDesc = desc != null && !desc.isEmpty();
		int blockH = hasDesc ? medium.lineHeight() + DESC_GAP + regular.lineHeight() : medium.lineHeight();
		int nameY = y + (UiTheme.CARD_H - blockH) / 2;
		int nameColor = Ui.lerpColor(UiTheme.TEXT_2, UiTheme.TEXT, Math.max(onv, hv * 0.8f));
		Ui.drawEllipsized(c, medium, title, textX, nameY, textRight - textX, nameColor);
		if (hasDesc) {
			Ui.drawEllipsized(c, regular, desc, textX, nameY + medium.lineHeight() + DESC_GAP,
					textRight - textX, UiTheme.TEXT_3);
		}

		// The settings chevron sits in its own strip between the name and the
		// switch. That strip is the hit target, so it is defined once.
		if (hasSettings()) {
			int cx = chevronLeft(w) + (CHEVRON_W - CHEVRON_SIZE) / 2;
			int cy = y + (UiTheme.CARD_H - CHEVRON_SIZE) / 2;
			int chev = Ui.lerpColor(UiTheme.TEXT_3, UiTheme.ACCENT, Math.max(hv, expand.value() * 0.8f));
			UiIcons.chevron(c, cx, cy, CHEVRON_SIZE, chev, expand.value());
		}

		// The switch.
		int tx = x + w - UiTheme.SP_5 - UiTheme.TOGGLE_W;
		int ty = y + (UiTheme.CARD_H - UiTheme.TOGGLE_H) / 2;
		toggle.update(module.isEnabled(), hovered, delta);
		toggle.render(c, tx, ty, UiTheme.TOGGLE_W, UiTheme.TOGGLE_H);

		// The settings well: an inset surface that holds the setting rows.
		int full = settingsFullHeight();
		if (full > 0 && expand.value() > 0.01f) {
			int clipH = Math.round(full * expand.value());
			c.pushClip(x + 1, y + UiTheme.CARD_H, w - 2, clipH);
			int wy = y + UiTheme.CARD_H;
			int inset = UiTheme.SP_3;
			Ui.surface(c, x + inset, wy, w - inset * 2, full - 2, UiTheme.RADIUS,
					UiTheme.WELL_TOP, UiTheme.WELL_BOTTOM);
			Ui.outline(c, x + inset, wy, w - inset * 2, full - 2, UiTheme.RADIUS, 0x26FFFFFF);

			int cy = wy + PAD;
			for (Setting<?> s : module.settings()) {
				cy += renderSetting(c, type, s, x, w, cy, mouseX, mouseY, delta);
			}
			c.popClip();
		}
	}

	/** Renders one setting row and returns the height it consumed. */
	private int renderSetting(UiCanvas c, UiType type, Setting<?> s, int x, int w, int rowY, int mouseX, int mouseY, float delta) {
		UiText regular = type.regular();
		int contentX = x + UiTheme.SP_6;
		int contentW = w - UiTheme.SP_6 * 2;

		if (s instanceof SliderSetting slider) {
			UiSlider ui = sliders.get(slider);
			int trackX0 = contentX;
			int trackX1 = x + w - UiTheme.SP_6;
			int trackY = rowY + 28;
			ui.bind(sliderLabels.get(slider), sliderValueText(slider), trackX0, trackX1, trackY);
			ui.render(c, regular, trackX0, trackX1 - trackX0);
			sliderBounds.put(slider, new int[]{trackX0, trackX1, rowY, rowY + ROW_SLIDER});
			return ROW_SLIDER;
		}

		if (s instanceof BooleanSetting bool) {
			UiToggle sw = switches.get(bool);
			String label = settingLabel(bool.key());
			int tw = UiTheme.TOGGLE_W;
			int th = UiTheme.TOGGLE_H;
			int tx = x + w - UiTheme.SP_6 - tw;
			int ty = rowY + (ROW_TOGGLE - th) / 2;
			boolean rowHovered = mouseX >= contentX && mouseX < x + w - UiTheme.SP_6
					&& mouseY >= rowY && mouseY < rowY + ROW_TOGGLE;
			sw.update(bool.value(), rowHovered, delta);
			sw.render(c, tx, ty, tw, th);
			Ui.drawEllipsized(c, regular, label, contentX, Ui.centerTextY(rowY, ROW_TOGGLE, regular),
					(tx - contentX) - UiTheme.SP_3, rowHovered ? UiTheme.TEXT : UiTheme.TEXT_2);
			switchBounds.put(bool, new int[]{contentX, x + w - UiTheme.SP_6, rowY, rowY + ROW_TOGGLE});
			return ROW_TOGGLE;
		}

		if (s instanceof WatchlistSetting watch) {
			UiWatchlist ui = watchlists.get(watch);
			ui.bind(contentX, rowY, contentW);
			ui.render(c, regular, contentX, rowY, contentW, mouseX, mouseY, delta);
			return settingHeight(s);
		}

		return 0;
	}

	private String sliderValueText(SliderSetting slider) {
		String unit = unitFor(slider);
		return unit.isEmpty() ? String.valueOf(slider.get()) : slider.get() + " " + unit;
	}

	private String unitFor(SliderSetting slider) {
		String key = "capy.module." + module.id() + "." + slider.key() + ".unit";
		String s = UiLabels.translate(key, null);
		return s == null ? "" : s;
	}

	// ---------------------------------------------------------------- input

	/** Full current bounds (card + any expanded settings). */
	public boolean contains(double mx, double my) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	/** Removes the keyboard focus from every text field of this card. */
	public void clearTextInput() {
		for (UiWatchlist ui : watchlists.values()) {
			ui.clearFocus();
		}
	}

	/** True while a text field inside the card owns the keyboard. */
	public boolean hasTextInput() {
		for (UiWatchlist ui : watchlists.values()) {
			if (ui.isFocused()) {
				return true;
			}
		}
		return false;
	}

	/** Returns true if the press was consumed by this card. */
	public boolean mouseClicked(double mx, double my, int button) {
		if (button != 0 || !contains(mx, my)) {
			return false;
		}

		// Setting controls consume first so they can never toggle the module.
		if (expanded) {
			if (mouseClickedSetting(mx, my)) {
				return true;
			}
			// Clicking the padding of the settings well is still a click on the
			// settings, not on the module switch.
			if (my >= y + UiTheme.CARD_H) {
				return true;
			}
		}

		// Chevron strip expands/collapses settings.
		if (overChevron(mx, my)) {
			expanded = !expanded;
			return true;
		}

		// Everything else (switch, name, body) toggles the module.
		pressed = true;
		module.toggle();
		return true;
	}

	/** Routes a click to the setting controls. */
	private boolean mouseClickedSetting(double mx, double my) {
		for (Map.Entry<SliderSetting, int[]> entry : sliderBounds.entrySet()) {
			int[] b = entry.getValue();
			if (mx >= b[0] && mx < b[1] && my >= b[2] && my < b[3]) {
				sliders.get(entry.getKey()).pressed(mx);
				return true;
			}
		}
		for (Map.Entry<BooleanSetting, int[]> entry : switchBounds.entrySet()) {
			int[] b = entry.getValue();
			if (mx >= b[0] && mx < b[1] && my >= b[2] && my < b[3]) {
				entry.getKey().toggle();
				return true;
			}
		}
		for (UiWatchlist ui : watchlists.values()) {
			if (ui.mouseClicked(mx, my)) {
				return true;
			}
		}
		return false;
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

	/** Routes typed characters to the card's text fields. */
	public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
		for (UiWatchlist ui : watchlists.values()) {
			if (ui.charTyped(event)) {
				return true;
			}
		}
		return false;
	}

	/** Routes key presses to the card's text fields. */
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		for (UiWatchlist ui : watchlists.values()) {
			if (ui.keyPressed(event)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Left edge of the chevron strip: immediately left of the switch, with a
	 * small gap. The renderer and the hit test both call this, which is what
	 * keeps the glyph under the pointer that actually expands the card.
	 */
	private int chevronLeft(int cardWidth) {
		return x + cardWidth - UiTheme.SP_5 - UiTheme.TOGGLE_W - UiTheme.SP_2 - CHEVRON_W;
	}

	private boolean overChevron(double mx, double my) {
		if (!hasSettings()) {
			return false;
		}
		int left = chevronLeft(w);
		return mx >= left && mx < left + CHEVRON_W && my >= y && my < y + UiTheme.CARD_H;
	}
}
