package com.capy.client.gui;

import com.capy.client.config.ClientConfig;
import com.capy.client.keybind.ClientKeybinds;
import com.capy.client.module.Category;
import com.capy.client.module.Module;
import com.capy.client.module.ModuleManager;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The Capy GUI: a plum window floating over the game.
 *
 * Structure: shell (shadow, surface, hairline border, drifting highlight) /
 * header (blossom mark, wordmark, page title, search, theme, close) /
 * sidebar (icon navigation, theme picker, keybind hint) / content workspace
 * (module cards or a designed empty state).
 *
 * Typography is Inter via {@link UiFonts}; every colour, gap and radius comes
 * from {@link UiTheme}; all drawing goes through {@link Ui}.
 *
 * Lifecycle: the open animation and the drifting petals run only while this
 * screen exists — closing discards the instance, so nothing animates or
 * listens afterwards. The keybind that opens the GUI is a single
 * edge-triggered listener in the entrypoint and is rebindable in the vanilla
 * Controls screen.
 */
public class ClickGuiScreen extends Screen {
	private static final Category[] CATEGORIES = Category.values();
	private static final UiIcons.Icon[] CATEGORY_ICONS = {
			UiIcons.Icon.TARGET, // Base Finder
			UiIcons.Icon.WORLD,
			UiIcons.Icon.VISUAL,
			UiIcons.Icon.DONUT,
			UiIcons.Icon.MISC
	};

	private final Map<Category, List<ModuleCard>> cards = new EnumMap<>(Category.class);
	private final UiScrollbar scrollbar = new UiScrollbar();
	private final UiSearch search = new UiSearch();
	private final UiDropdown themePicker = new UiDropdown();
	private final UiPetals petals = new UiPetals();
	private final UiTooltip tooltip = new UiTooltip();

	private final Anim open = new Anim(0f, 14f);
	private final float[] select = new float[CATEGORIES.length];

	private int selected;
	private int lastSelected = -1;
	/** Cached search result; rebuilt only when the query changes. */
	private String cachedQuery = null;
	private final List<ModuleCard> cachedMatches = new ArrayList<>();
	private float switchProgress = 1f;
	private boolean closing;
	private ModuleCard pressedCard;
	private int themeIndex;

	public ClickGuiScreen() {
		super(Component.translatable("capy.gui.title"));
	}

	@Override
	protected void init() {
		cards.clear();
		for (Category category : CATEGORIES) {
			List<ModuleCard> list = new ArrayList<>();
			for (Module module : ModuleManager.get().byCategory(category)) {
				list.add(new ModuleCard(module));
			}
			cards.put(category, list);
		}
		selected = 0;
		lastSelected = -1;
		cachedQuery = null;
		cachedMatches.clear();
		switchProgress = 1f;
		closing = false;
		search.setFocused(false);
		themePicker.close();
		themeIndex = indexOfTheme(ClientConfig.get().theme());
		themePicker.setOptions(themeLabels(), themeIndex);
		open.to(0f, 0f);
		petals.reset(this.width, this.height);
	}

	/** Keeps layout working across window resizes without rebuilding state. */
	@Override
	public void resize(int width, int height) {
		this.width = width;
		this.height = height;
		petals.reset(width, height);
	}

	// ---------------------------------------------------------------- layout

	private int windowWidth() {
		return Math.max(460, Math.min(660, (int) (this.width * 0.60f)));
	}

	private int windowHeight() {
		return Math.max(320, Math.min(420, (int) (this.height * 0.78f)));
	}

	private float openScale() {
		float t = open.value();
		return 0.96f + 0.04f * t;
	}

	private int windowX() {
		float s = openScale();
		return (int) (this.width / 2.0 - windowWidth() * s / 2.0);
	}

	private int windowY() {
		float s = openScale();
		return (int) (this.height / 2.0 - windowHeight() * s / 2.0);
	}

	/** Plays the outro animation, then really closes. */
	public void requestClose() {
		if (!closing) {
			closing = true;
			search.setFocused(false);
			open.to(0f, 1f);
		}
	}

	private int shellX() {
		return windowX();
	}

	private int shellY() {
		return windowY();
	}

	private int shellW() {
		return Math.round(windowWidth() * openScale());
	}

	private int shellH() {
		return Math.round(windowHeight() * openScale());
	}

	/** Scales a horizontal offset by the open animation. */
	private int sx(int value) {
		return Math.round(value * openScale());
	}

	/** Scales a vertical offset by the open animation. */
	private int sy(int value) {
		return Math.round(value * openScale());
	}

	private boolean inWindow(double mx, double my) {
		int wx = shellX();
		int wy = shellY();
		int ww = shellW();
		int wh = shellH();
		return mx >= wx && mx < wx + ww && my >= wy && my < wy + wh;
	}

	private boolean inContent(double mx, double my) {
		int cx = contentX();
		int top = contentTop();
		int bottom = contentBottom();
		int cw = contentW();
		return mx >= cx && mx < cx + cw && my >= top && my < bottom;
	}

	private int contentX() {
		return shellX() + sx(UiTheme.SIDEBAR_W + UiTheme.SP_4);
	}

	private int contentTop() {
		return shellY() + sy(UiTheme.HEADER_H + UiTheme.SP_5);
	}

	private int contentBottom() {
		return shellY() + shellH() - sy(UiTheme.SP_4);
	}

	private int contentW() {
		int wx = shellX();
		int ww = shellW();
		int cx = contentX();
		// Leave a gutter on the right so the scrollbar thumb stays inside the shell.
		return wx + ww - sx(UiTheme.SP_4) - sx(10) - cx;
	}

	// ---------------------------------------------------------------- render

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		// Fade: the scrim eases in from transparent; skip the shell while invisible.
		open.to(closing ? 0f : 1f, partialTick);
		float fade = open.value();
		g.fill(0, 0, this.width, this.height, Ui.lerpColor(0x00000000, UiTheme.SCRIM, fade));
		if (fade <= 0.03f) {
			if (closing) {
				finishClose();
			}
			return;
		}

		petals.update(partialTick, this.width, this.height);
		petals.render(g);

		for (int i = 0; i < select.length; i++) {
			select[i] += ((i == selected ? 1f : 0f) - select[i]) * (1f - (float) Math.exp(-16f * partialTick));
		}
		if (selected != lastSelected) {
			lastSelected = selected;
			switchProgress = 0f;
		}
		switchProgress += (1f - switchProgress) * (1f - (float) Math.exp(-14f * partialTick));

		int wx = shellX();
		int wy = shellY();
		int ww = shellW();
		int wh = shellH();
		int r = UiTheme.RADIUS_XL;

		Ui.shadow(g, wx, wy, ww, wh, r);
		Ui.surface(g, wx, wy, ww, wh, r, UiTheme.SHELL_TOP, UiTheme.SHELL_BOTTOM);
		Ui.sheen(g, wx, wy, ww, wh, r, sheenPhase());
		Ui.outline(g, wx, wy, ww, wh, r, UiTheme.BORDER);
		Ui.innerBevel(g, wx, wy, ww, wh, r, UiTheme.HIGHLIGHT, UiTheme.SHADE);

		renderHeader(g, wx, wy, ww, mouseX, mouseY, partialTick);
		renderSidebar(g, mouseX, mouseY, partialTick);
		renderContent(g, mouseX, mouseY, partialTick);

		// The theme list and the tooltip are drawn last so they float above
		// every panel.
		themePicker.renderOverlay(g, UiFonts.regular(), mouseX, mouseY, partialTick);
		tooltip.render(g, UiFonts.regular());
	}

	/** Slow drift of the specular band, so the window never looks static. */
	private float sheenPhase() {
		double t = (System.currentTimeMillis() % 9000L) / 9000.0;
		return (float) (0.5 + 0.5 * Math.sin(t * Math.PI * 2.0));
	}

	private void renderHeader(GuiGraphics g, int wx, int wy, int ww, int mouseX, int mouseY, float partialTick) {
		int cy = wy + (UiTheme.HEADER_H - 18) / 2;
		int markX = wx + sx(UiTheme.SP_5);
		int mark = 18;

		// Blossom mark: a small plum disc with a blossom drawn on top.
		Ui.surface(g, markX, cy, mark, mark, 9, UiTheme.PANEL_ALT, UiTheme.PANEL_TOP);
		Ui.outline(g, markX, cy, mark, mark, 9, UiTheme.BORDER);
		UiIcons.draw(g, UiIcons.Icon.BLOSSOM, markX + 3, cy + 3, 12, UiTheme.ACCENT);

		// Wordmark + page title.
		int nx = markX + mark + sx(UiTheme.SP_3);
		int ny = wy + (UiTheme.HEADER_H - UiFonts.bold().lineHeight) / 2;
		net.minecraft.client.gui.Font name = UiFonts.display();
		g.drawString(name, "Capy Client", nx, ny, UiTheme.TEXT, false);

		int fieldX = searchX();
		int pageX = nx + sx(name.width("Capy Client") + UiTheme.SP_4);
		if (fieldX - pageX > sx(UiTheme.SP_5)) {
			String page = search.query().isEmpty() ? CATEGORIES[selected].title : "Search";
			g.fill(pageX - sx(UiTheme.SP_2), ny + name.lineHeight / 2 - 1, pageX - sx(UiTheme.SP_2) + 1,
					ny + name.lineHeight / 2, UiTheme.ACCENT);
			Ui.drawEllipsized(g, UiFonts.medium(), page, pageX, ny + 1, fieldX - pageX - sx(UiTheme.SP_3),
					UiTheme.TEXT_2);
		}

		// Search field.
		search.bind(fieldX, wy + (UiTheme.HEADER_H - UiTheme.SEARCH_H) / 2, UiTheme.SEARCH_W, UiTheme.SEARCH_H);
		search.render(g, UiFonts.regular(), mouseX, mouseY, partialTick);

		// Close button.
		int bx = wx + ww - sx(UiTheme.SP_5) - UiTheme.CLOSE_BTN;
		int by = wy + (UiTheme.HEADER_H - UiTheme.CLOSE_BTN) / 2;
		boolean hovered = mouseX >= bx && mouseX < bx + UiTheme.CLOSE_BTN
				&& mouseY >= by && mouseY < by + UiTheme.CLOSE_BTN;
		Ui.surface(g, bx, by, UiTheme.CLOSE_BTN, UiTheme.CLOSE_BTN, UiTheme.RADIUS,
				hovered ? UiTheme.PANEL_ALT : UiTheme.TRACK_TOP,
				hovered ? UiTheme.PANEL_TOP : UiTheme.TRACK_BOTTOM);
		Ui.outline(g, bx, by, UiTheme.CLOSE_BTN, UiTheme.CLOSE_BTN, UiTheme.RADIUS,
				hovered ? UiTheme.ACCENT : UiTheme.BORDER);
		UiIcons.draw(g, UiIcons.Icon.CLOSE, bx + 8, by + 8, 8, hovered ? UiTheme.TEXT : UiTheme.TEXT_2);

		// A delicate pink line separates the header from the workspace. It
		// fades out at both ends instead of ending abruptly.
		int lineY = wy + sy(UiTheme.HEADER_H) - 1;
		int lineX0 = shellX() + sx(UiTheme.SIDEBAR_W + UiTheme.SP_4);
		int lineX1 = wx + ww - sx(UiTheme.SP_5);
		if (lineX1 - lineX0 > 8) {
			Ui.fadeLine(g, lineX0, lineY, lineX1 - lineX0, Ui.withAlpha(UiTheme.ACCENT, 0x8C));
		}
	}

	/** Search field sits to the left of the close button, never behind it. */
	private int searchX() {
		return shellX() + shellW() - sx(UiTheme.SP_5) - UiTheme.CLOSE_BTN
				- sx(UiTheme.SP_3) - UiTheme.SEARCH_W;
	}

	private int closeX() {
		return shellX() + shellW() - sx(UiTheme.SP_5) - UiTheme.CLOSE_BTN;
	}

	private void renderSidebar(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		int wx = shellX();
		int wy = shellY();
		int wh = shellH();
		int px = wx + sx(UiTheme.SP_3);
		int py = wy + sy(UiTheme.HEADER_H) - sy(UiTheme.SP_1);
		int pw = sx(UiTheme.SIDEBAR_W);
		int ph = wh - sy(UiTheme.HEADER_H) + sy(UiTheme.SP_1) - sy(UiTheme.SP_3);

		// The sidebar is its own surface, set into the shell.
		Ui.surface(g, px, py, pw, ph, UiTheme.RADIUS_LG, UiTheme.PANEL_TOP, UiTheme.PANEL_BOTTOM);
		Ui.outline(g, px, py, pw, ph, UiTheme.RADIUS_LG, UiTheme.BORDER);

		int sx0 = px + sx(UiTheme.SP_2);
		int sw = pw - sx(UiTheme.SP_2) * 2;
		int y = py + sy(UiTheme.SP_3);

		// Section label.
		Ui.drawEllipsized(g, UiFonts.regular(), "CATEGORIES", sx0 + sx(UiTheme.SP_2), y,
				sw - sx(UiTheme.SP_3), UiTheme.TEXT_3);
		y += sy(14);

		int rowH = sy(28);
		int gap = sy(3);

		for (int i = 0; i < CATEGORIES.length; i++) {
			Category category = CATEGORIES[i];
			boolean hovered = mouseX >= sx0 && mouseX < sx0 + sw && mouseY >= y && mouseY < y + rowH;
			float sel = select[i];
			int rr = UiTheme.RADIUS;

			// Selected row: an accent wash with a leading accent bar.
			if (sel > 0.02f) {
				int top = Ui.lerpColor(0x00000000, Ui.withAlpha(UiTheme.ACCENT, 0x2E), sel);
				int bot = Ui.lerpColor(0x00000000, Ui.withAlpha(UiTheme.ACCENT_DEEP, 0x14), sel);
				Ui.surface(g, sx0, y, sw, rowH, rr, top, bot);
				int barH = Math.round((rowH - 10) * sel);
				Ui.surface(g, sx0 + 2, y + (rowH - barH) / 2, 3, barH, 2,
						Ui.withAlpha(UiTheme.ACCENT, Math.round(255 * sel)),
						Ui.withAlpha(UiTheme.ACCENT_DEEP, Math.round(255 * sel)));
			} else if (hovered) {
				Ui.surface(g, sx0, y, sw, rowH, rr, Ui.withAlpha(UiTheme.PANEL_ALT, 0x9A),
						Ui.withAlpha(UiTheme.PANEL_TOP, 0x7A));
			}

			int iconBox = sy(14);
			int textX = sx0 + sx(UiTheme.SP_3) + iconBox + sx(UiTheme.SP_2);
			int textY = y + (rowH - UiFonts.medium().lineHeight) / 2;
			// The icon carries the category accent, the active state promotes it
			// to the client's pink.
			int resting = Ui.withAlpha(category.accent, 0xB4);
			int iconColor = Ui.lerpColor(resting, UiTheme.ACCENT, Math.max(sel, hovered ? 0.9f : 0f));
			UiIcons.draw(g, CATEGORY_ICONS[i], sx0 + sx(UiTheme.SP_3), y + (rowH - iconBox) / 2, iconBox, iconColor);

			int nameColor = Ui.lerpColor(UiTheme.TEXT_2, UiTheme.TEXT, Math.max(sel, hovered ? 0.8f : 0f));
			Ui.drawEllipsized(g, UiFonts.medium(), category.title, textX, textY,
					sx0 + sw - sx(UiTheme.SP_3) - textX, nameColor);

			// Count badge only when meaningful.
			int count = cards.get(category).size();
			if (count > 0) {
				String label = String.valueOf(count);
				net.minecraft.client.gui.Font f = UiFonts.regular();
				int bw = f.width(label) + sx(8);
				int bx = sx0 + sw - sx(UiTheme.SP_3) - bw;
				int by = y + (rowH - 12) / 2;
				Ui.surface(g, bx, by, bw, 12, 6,
						Ui.lerpColor(Ui.withAlpha(UiTheme.PANEL_ALT, 0xCC), Ui.withAlpha(UiTheme.ACCENT, 0x4D), sel),
						Ui.lerpColor(Ui.withAlpha(UiTheme.PANEL_TOP, 0xCC), Ui.withAlpha(UiTheme.ACCENT_DEEP, 0x33), sel));
				Ui.drawCentered(g, f, label, bx + bw / 2, by + (12 - f.lineHeight) / 2,
						Ui.lerpColor(UiTheme.TEXT_3, UiTheme.TEXT, sel));
			}

			y += rowH + gap;
		}

		// Theme picker at the foot of the sidebar.
		int pickerH = sy(20);
		int pickerY = py + ph - sy(46);
		themePicker.bind(sx0 + sx(UiTheme.SP_1), pickerY, sw - sx(UiTheme.SP_2), pickerH, true);
		themePicker.render(g, UiFonts.regular(), mouseX, mouseY, partialTick);
		Ui.drawEllipsized(g, UiFonts.regular(), "Theme", sx0 + sx(UiTheme.SP_2), pickerY - sy(11),
				sw - sx(UiTheme.SP_3), UiTheme.TEXT_3);

		// Keybind hint, with a tooltip that explains where it can be changed.
		String hint = ClientKeybinds.openClickGui() == null
				? "?"
				: ClientKeybinds.openClickGui().getTranslatedKeyMessage().getString();
		int hintY = py + ph - sy(16);
		int hintX = sx0 + sx(UiTheme.SP_2);
		Ui.drawEllipsized(g, UiFonts.regular(), hint + " to close", hintX, hintY,
				sw - sx(UiTheme.SP_2), UiTheme.TEXT_3);
		boolean hintHovered = mouseX >= hintX && mouseX < hintX + sw - sx(UiTheme.SP_2)
				&& mouseY >= hintY - 2 && mouseY < hintY + UiFonts.regular().lineHeight + 2;
		if (hintHovered) {
			tooltip.show("Esc closes the GUI. Rebind the key in Options \u2192 Controls.", hintX, hintY);
		} else {
			tooltip.hide();
		}
	}

	private void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		int cx = contentX();
		int top = contentTop();
		int bottom = contentBottom();
		int cw = contentW();
		int viewportH = Math.max(0, bottom - top);

		List<ModuleCard> list = visibleCards();
		boolean showCategory = !search.query().isEmpty();

		// Category-switch transition: the content slides in.
		if (!showCategory && switchProgress < 0.999f) {
			top += sy(Math.round((1f - switchProgress) * 8f));
		}

		// Measure with current animated heights.
		int contentH = 0;
		for (int i = 0; i < list.size(); i++) {
			ModuleCard card = list.get(i);
			card.animate(partialTick);
			contentH += card.height();
			if (i < list.size() - 1) {
				contentH += UiTheme.CARD_GAP;
			}
		}

		scrollbar.update(top, bottom, contentH, partialTick);
		scrollbar.setRailX(contentRailX());

		if (list.isEmpty()) {
			renderEmptyState(g, cx + cw / 2, top + viewportH / 2, showCategory);
			return;
		}

		boolean clipped = Ui.beginClip(g, cx, top, cw, viewportH);
		int y = top - (int) Math.round(scrollbar.scroll());
		for (int i = 0; i < list.size(); i++) {
			ModuleCard card = list.get(i);
			if (y + card.height() >= top && y <= bottom) {
				card.render(g, cx, y, cw, mouseX, mouseY, partialTick, showCategory);
			}
			y += card.height() + UiTheme.CARD_GAP;
		}
		if (clipped) {
			Ui.endClip(g);
		}

		scrollbar.render(g);
	}

	/**
	 * The cards to show. The search result is cached and only rebuilt when the
	 * query actually changes, so a frame never allocates a new list.
	 */
	private List<ModuleCard> visibleCards() {
		String q = search.query().trim().toLowerCase(Locale.ROOT);
		if (q.isEmpty()) {
			cachedQuery = null;
			return cards.get(CATEGORIES[selected]);
		}
		if (!q.equals(cachedQuery)) {
			cachedQuery = q;
			cachedMatches.clear();
			for (Category category : CATEGORIES) {
				for (ModuleCard card : cards.get(category)) {
					if (card.title().toLowerCase(Locale.ROOT).contains(q)
							|| card.descriptionText().toLowerCase(Locale.ROOT).contains(q)
							|| card.module().id().replace('_', ' ').contains(q)) {
						cachedMatches.add(card);
					}
				}
			}
		}
		return cachedMatches;
	}

	private void renderEmptyState(GuiGraphics g, int centerX, int centerY, boolean searchEmpty) {
		net.minecraft.client.gui.Font regular = UiFonts.regular();
		net.minecraft.client.gui.Font medium = UiFonts.medium();
		String title = searchEmpty ? "No matches" : "Nothing here yet";
		String sub = searchEmpty
				? "No modules match your search."
				: "This category has no modules yet.";

		// A blossom in a soft circle, so the empty state still belongs to the theme.
		int orb = 34;
		int oy = centerY - 34;
		Ui.surface(g, centerX - orb / 2, oy, orb, orb, orb / 2, UiTheme.PANEL_ALT, UiTheme.PANEL_TOP);
		Ui.outline(g, centerX - orb / 2, oy, orb, orb, orb / 2, UiTheme.BORDER);
		UiIcons.draw(g, searchEmpty ? UiIcons.Icon.SEARCH : UiIcons.Icon.BLOSSOM,
				centerX - 8, oy + 8, 16, UiTheme.TEXT_3);

		Ui.drawCentered(g, medium, title, centerX, oy + orb + 10, UiTheme.TEXT_2);
		Ui.drawCentered(g, regular, sub, centerX, oy + orb + 26, UiTheme.TEXT_3);
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		double mx = event.x();
		double my = event.y();
		int button = event.button();

		if (closing || !inWindow(mx, my)) {
			return true; // swallow everything while closing / outside the shell
		}

		// A mouse button bound to the GUI closes it as well.
		if (ClientKeybinds.openClickGui() != null && ClientKeybinds.openClickGui().matchesMouse(event)) {
			requestClose();
			return true;
		}

		// The theme list floats above everything, so it gets the first refusal.
		if (themePicker.isExpanded() && themePicker.mouseClicked(mx, my)) {
			applyThemeIfChanged();
			return true;
		}

		// Search field first (it sits in the header).
		if (button == 0 && search.contains(mx, my)) {
			if (search.isOnClear(mx, my)) {
				search.clear();
			} else {
				search.setFocused(true);
			}
			return true;
		}
		search.setFocused(false);
		// A click outside a text field gives the keyboard back to the GUI.
		for (Category category : CATEGORIES) {
			for (ModuleCard card : cards.get(category)) {
				card.clearTextInput();
			}
		}

		int wx = shellX();
		int wy = shellY();

		// Close button.
		if (button == 0) {
			int bx = closeX();
			int by = wy + (UiTheme.HEADER_H - UiTheme.CLOSE_BTN) / 2;
			if (mx >= bx && mx < bx + UiTheme.CLOSE_BTN && my >= by && my < by + UiTheme.CLOSE_BTN) {
				onClose();
				return true;
			}
		}

		// Sidebar rows.
		int px = wx + sx(UiTheme.SP_3);
		int py = wy + sy(UiTheme.HEADER_H) - sy(UiTheme.SP_1);
		int pw = sx(UiTheme.SIDEBAR_W);
		int sx0 = px + sx(UiTheme.SP_2);
		int sw = pw - sx(UiTheme.SP_2) * 2;
		int rowY = py + sy(UiTheme.SP_3) + sy(14);
		int rowH = sy(28);
		int gap = sy(3);
		if (mx >= sx0 && mx < sx0 + sw && my >= rowY) {
			int index = (int) ((my - rowY) / (rowH + gap));
			if (index >= 0 && index < CATEGORIES.length) {
				selected = index;
				search.clear();
				return true;
			}
		}

		// Theme picker.
		if (themePicker.contains(mx, my)) {
			themePicker.mouseClicked(mx, my);
			applyThemeIfChanged();
			return true;
		}

		// Scrollbar rail first: it sits in the gutter, outside the card viewport.
		int railX = contentRailX();
		if (mx >= railX - 3 && mx < railX + 8 && my >= contentTop() && my < contentBottom()) {
			scrollbar.pressed(my);
			return true;
		}

		// Content area: cards.
		if (inContent(mx, my)) {
			List<ModuleCard> list = visibleCards();
			for (int i = list.size() - 1; i >= 0; i--) {
				ModuleCard card = list.get(i);
				if (card.contains(mx, my) && card.mouseClicked(mx, my, button)) {
					pressedCard = card;
					return true;
				}
			}
		}
		return true;
	}

	private int contentRailX() {
		return contentX() + contentW() + sx(UiTheme.SP_3);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		double mx = event.x();
		double my = event.y();
		if (pressedCard != null) {
			pressedCard.mouseDragged(mx, my);
			return true;
		}
		if (scrollbar.isDragging()) {
			scrollbar.dragged(my);
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (pressedCard != null) {
			pressedCard.mouseReleased(event.x(), event.y());
			pressedCard = null;
			return true;
		}
		if (scrollbar.released()) {
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double xAmount, double yAmount) {
		if (inWindow(mouseX, mouseY) && scrollbar.onScroll(yAmount)) {
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, xAmount, yAmount);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (closing) {
			return true;
		}
		if (search.charTyped(event)) {
			return true;
		}
		// Card text fields (the Anti Admin watchlist) get the character.
		for (Category category : CATEGORIES) {
			for (ModuleCard card : cards.get(category)) {
				if (card.charTyped(event)) {
					return true;
				}
			}
		}
		return super.charTyped(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		// A focused text field owns the keyboard: the client's keybind must not
		// close the GUI while the player is typing.
		if (!hasTextInput()) {
			if (ClientKeybinds.openClickGui().matches(event)) {
				requestClose();
				return true;
			}
		}
		if (search.keyPressed(event)) {
			return true;
		}
		for (Category category : CATEGORIES) {
			for (ModuleCard card : cards.get(category)) {
				if (card.keyPressed(event)) {
					return true;
				}
			}
		}
		return super.keyPressed(event);
	}

	/** True when any text field of the GUI currently owns the keyboard. */
	private boolean hasTextInput() {
		if (search.isFocused()) {
			return true;
		}
		for (Category category : CATEGORIES) {
			for (ModuleCard card : cards.get(category)) {
				if (card.hasTextInput()) {
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public void onClose() {
		// Esc / close button: play the outro, then close for real.
		requestClose();
	}

	private void finishClose() {
		pressedCard = null;
		scrollbar.released();
		search.setFocused(false);
		// Store whatever the player changed in the GUI (watchlist, ranges, ...).
		ClientConfig.get().save(FabricLoader.getInstance().getConfigDir());
		super.onClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ---------------------------------------------------------------- theme

	private List<String> themeLabels() {
		List<String> labels = new ArrayList<>();
		for (UiTheme.Palette palette : UiTheme.palettes()) {
			labels.add(palette.label());
		}
		return labels;
	}

	private int indexOfTheme(String themeId) {
		List<UiTheme.Palette> palettes = UiTheme.palettes();
		for (int i = 0; i < palettes.size(); i++) {
			if (palettes.get(i).id().equals(themeId)) {
				return i;
			}
		}
		return 0;
	}

	/** Applies the palette the player picked, if it changed. */
	private void applyThemeIfChanged() {
		int index = themePicker.selected();
		if (index == themeIndex) {
			return;
		}
		themeIndex = index;
		applyTheme(index);
	}

	/** Applies the palette the player picked and stores the choice. */
	private void applyTheme(int index) {
		List<UiTheme.Palette> palettes = UiTheme.palettes();
		if (index < 0 || index >= palettes.size()) {
			return;
		}
		UiTheme.setPalette(palettes.get(index).id());
		ClientConfig config = ClientConfig.get();
		config.setTheme(palettes.get(index).id());
		config.save(FabricLoader.getInstance().getConfigDir());
	}
}
