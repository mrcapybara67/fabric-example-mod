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
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * The Capy window: everything that is drawn, minus the Minecraft plumbing.
 *
 * The frame owns the layout, the animation state and the hit testing; the
 * screen only forwards Minecraft events to it. Keeping the two apart means
 * the whole interface can be rendered outside the game for visual review.
 *
 * Layout rules that are applied everywhere:
 *   <ul>
 *     <li>one spacing scale ({@link UiTheme#SP_1} .. {@link UiTheme#SP_8});</li>
 *     <li>one radius scale ({@link UiTheme#RADIUS} .. {@link UiTheme#RADIUS_XL});</li>
 *     <li>every surface is built from the same three steps: shadow, body,
 *         hairline - so the interface reads as one material.</li>
 *   </ul>
 */
public final class GuiFrame {
	private static final Category[] CATEGORIES = Category.values();
	/** Height of the section header above the card list. */
	private static final int SECTION_HEADER_H = 26;

	/**
	 * The space the sidebar needs: the categories, the theme picker and the
	 * keybind hint. The window never gets shorter than this.
	 */
	private static final int SIDEBAR_MIN_H = 348;

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

	private final Anim windowHeight = new Anim(0f, 12f);
	private final float[] select = new float[CATEGORIES.length];
	private final List<ModuleCard> cachedMatches = new ArrayList<>();

	private int selected;
	private int lastSelected = -1;
	private float switchProgress = 1f;
	private String cachedQuery;
	private int themeIndex;
	private int width;
	private int height;
	private boolean closeRequested;

	public GuiFrame() {
		rebuild();
		themeIndex = indexOfTheme(ClientConfig.get().theme());
		themePicker.setOptions(themeLabels(), themeIndex);
	}

	/** Rebuilds the card list from the registered modules. */
	public void rebuild() {
		int previous = selected;
		cards.clear();
		for (Category category : CATEGORIES) {
			List<ModuleCard> list = new ArrayList<>();
			for (Module module : ModuleManager.get().byCategory(category)) {
				list.add(new ModuleCard(module));
			}
			cards.put(category, list);
		}
		// Keep the current page when the list is rebuilt.
		selected = Math.max(0, Math.min(CATEGORIES.length - 1, previous));
		lastSelected = -1;
		cachedQuery = null;
		cachedMatches.clear();
	}

	/** Updates the layout for a new screen size. */
	public void resize(int width, int height) {
		this.width = width;
		this.height = height;
		petals.reset(width, height);
		windowHeight.snap(targetWindowHeight());
	}

	/** Selects a category by index (used by the offline preview). */
	void setCategory(int index) {
		if (index >= 0 && index < CATEGORIES.length) {
			selected = index;
		}
	}

	/** Expands a module card (used by the offline preview). */
	void expandCard(Category category, int cardIndex) {
		List<ModuleCard> list = cards.get(category);
		if (list != null && cardIndex >= 0 && cardIndex < list.size()) {
			list.get(cardIndex).setExpanded(true);
		}
	}

	/** Fills the search field (used by the offline preview). */
	void setSearch(String query) {
		search.setQuery(query);
	}

	/** True when the frame asked to be closed. */
	public boolean closeRequested() {
		return closeRequested;
	}

	// ---------------------------------------------------------------- layout

	private int windowHeight() {
		return Math.round(windowHeight.value());
	}

	private int windowWidth() {
		return Math.max(520, Math.min(760, (int) (width * 0.62f)));
	}

	/**
	 * The height the window wants for its content. A window that is much
	 * taller than its content looks empty, so the shell follows the modules
	 * and grows only when the content needs the room.
	 */
	private int targetWindowHeight() {
		int content = 0;
		List<ModuleCard> list = visibleCards();
		for (int i = 0; i < list.size(); i++) {
			content += list.get(i).fullHeight();
			if (i < list.size() - 1) {
				content += UiTheme.CARD_GAP;
			}
		}
		int needed = UiTheme.HEADER_H + UiTheme.SP_6 + SECTION_HEADER_H + content + UiTheme.SP_6;
		int max = Math.min(520, (int) (height * 0.82f));
		int min = Math.min(max, SIDEBAR_MIN_H);
		return Math.max(min, Math.min(max, needed));
	}

	private int windowX() {
		return (width - windowWidth()) / 2;
	}

	private int windowY() {
		return (height - windowHeight()) / 2;
	}

	private int contentX() {
		return windowX() + UiTheme.SIDEBAR_W + UiTheme.SP_6;
	}

	private int contentW() {
		return windowX() + windowWidth() - UiTheme.SP_6 - UiTheme.SP_5 - contentX();
	}

	private int contentTop() {
		return windowY() + UiTheme.HEADER_H + UiTheme.SP_6;
	}

	private int contentBottom() {
		return windowY() + windowHeight() - UiTheme.SP_5;
	}

	// ---------------------------------------------------------------- render

	/**
	 * Draws the whole window.
	 *
	 * @param open 0..1, drives the opening and closing transition
	 */
	public void render(UiCanvas c, UiType type, int mouseX, int mouseY, float delta, float open) {
		float fade = Motion.clamp01(open);
		if (fade <= 0.02f) {
			return;
		}
		// The backdrop: a plum veil that is darkest at the edges, so the
		// window reads as the lit object in the middle.
		c.fill(0, 0, width, height, Ui.lerpColor(0x00000000, UiTheme.SCRIM, fade));
		int steps = 16;
		for (int i = 0; i < steps; i++) {
			float t = i / (float) steps;
			int a = Math.round(70 * fade * (1f - t));
			if (a <= 0) {
				continue;
			}
			int shade = (a << 24) | 0x00000000;
			int inset = i * 5;
			c.fill(inset, inset, width - inset, inset + 5, shade);
			c.fill(inset, height - inset - 5, width - inset, height - inset, shade);
			c.fill(inset, inset, inset + 5, height - inset, shade);
			c.fill(width - inset - 5, inset, width - inset, height - inset, shade);
		}

		// The window follows its content: it grows when a module opens its
		// settings and shrinks back when it closes.
		windowHeight.to(targetWindowHeight(), delta);
		if (windowHeight.value() <= 1f) {
			windowHeight.snap(targetWindowHeight());
		}

		petals.update(delta, width, height);
		petals.render(c, UiPetals.Layer.BACK, fade);

		// The window settles into place: it rises a little and scales up
		// very slightly while it fades in.
		float ease = Motion.easeOutCubic(fade);
		int ww = Math.round(windowWidth() * (0.965f + 0.035f * ease));
		int wh = Math.round(windowHeight() * (0.965f + 0.035f * ease));
		int wx = (width - ww) / 2;
		int wy = (height - wh) / 2 + Math.round((1f - ease) * 10f);
		int r = UiTheme.RADIUS_XL;

		Ui.shadow(c, wx, wy, ww, wh, r);
		Ui.surface(c, wx, wy, ww, wh, r, UiTheme.SHELL_TOP, UiTheme.SHELL_BOTTOM);
		// A soft light from above: the top of the window catches the light.
		Ui.glow(c, wx + 2, wy + 2, ww - 4, Math.min(wh, 90), UiTheme.PALE_CHERRY, 0.05f);
		Ui.sheen(c, wx, wy, ww, wh, r, sheenPhase());
		Ui.outline(c, wx, wy, ww, wh, r, UiTheme.BORDER);
		Ui.innerBevel(c, wx, wy, ww, wh, r, UiTheme.HIGHLIGHT, UiTheme.SHADE);

		// The accent reveal: a soft pink light that washes over the window as
		// it opens.
		if (fade < 1f) {
			Ui.glow(c, wx, wy, ww, Math.round(wh * 0.5f), UiTheme.ACCENT, (1f - fade) * 0.35f);
		}

		for (int i = 0; i < select.length; i++) {
			select[i] = Motion.approach(select[i], i == selected ? 1f : 0f, 14f, delta / 20f);
		}
		if (selected != lastSelected) {
			lastSelected = selected;
			switchProgress = 0f;
		}
		switchProgress = Motion.approach(switchProgress, 1f, 12f, delta / 20f);

		renderHeader(c, type, wx, wy, ww, mouseX, mouseY, delta);
		renderSidebar(c, type, wx, wy, ww, wh, mouseX, mouseY, delta);
		renderContent(c, type, mouseX, mouseY, delta);

		// A few petals pass in front of the window for depth. They are very
		// faint so they never compete with the content.
		petals.render(c, UiPetals.Layer.FRONT, fade * 0.7f);

		themePicker.renderOverlay(c, type.regular(), mouseX, mouseY, delta);
		tooltip.render(c, type.regular());
	}

	/** Slow drift of the specular band, so the window never looks static. */
	private float sheenPhase() {
		double t = (System.currentTimeMillis() % 11000L) / 11000.0;
		return (float) (0.5 + 0.5 * Math.sin(t * Math.PI * 2.0));
	}

	private void renderHeader(UiCanvas c, UiType type, int wx, int wy, int ww, int mouseX, int mouseY, float delta) {
		int cy = wy + (UiTheme.HEADER_H - 34) / 2;

		// The mark: a plum disc carrying the blossom, with a soft glow.
		int mark = 34;
		int markX = wx + UiTheme.SP_6;
		Ui.surface(c, markX, cy, mark, mark, 12, UiTheme.PANEL_ALT, UiTheme.PANEL_TOP);
		Ui.outline(c, markX, cy, mark, mark, 12, UiTheme.BORDER);
		UiIcons.draw(c, UiIcons.Icon.BLOSSOM, markX + 7, cy + 7, 20, UiTheme.ACCENT);
		Ui.glow(c, markX - 6, cy - 6, mark + 12, mark + 12, UiTheme.ACCENT, 0.16f);

		// Wordmark.
		UiText display = type.display();
		int nameX = markX + mark + UiTheme.SP_4;
		int nameY = wy + (UiTheme.HEADER_H - display.lineHeight()) / 2 - 3;
		display.draw(c, "Capy Client", nameX, nameY, UiTheme.TEXT);

		// The current page, separated from the wordmark by a hairline.
		int pageX = nameX + display.width("Capy Client") + UiTheme.SP_4;
		int fieldX = searchX(wx, ww);
		if (fieldX - pageX > UiTheme.SP_6 * 2) {
			Ui.surface(c, pageX - UiTheme.SP_3, nameY + 3, 1, display.lineHeight() - 4, 0, UiTheme.BORDER);
			String page = search.query().isEmpty() ? CATEGORIES[selected].title : "Search results";
			Ui.drawEllipsized(c, type.medium(), page, pageX + UiTheme.SP_3, nameY + 1,
					fieldX - pageX - UiTheme.SP_6, UiTheme.TEXT_2);
		}

		// Search.
		search.bind(fieldX, wy + (UiTheme.HEADER_H - UiTheme.SEARCH_H) / 2, UiTheme.SEARCH_W, UiTheme.SEARCH_H);
		search.render(c, type.regular(), mouseX, mouseY, delta);

		// Close.
		int bx = wx + ww - UiTheme.SP_6 - UiTheme.CLOSE_BTN;
		int by = wy + (UiTheme.HEADER_H - UiTheme.CLOSE_BTN) / 2;
		boolean hovered = mouseX >= bx && mouseX < bx + UiTheme.CLOSE_BTN
				&& mouseY >= by && mouseY < by + UiTheme.CLOSE_BTN;
		Ui.surface(c, bx, by, UiTheme.CLOSE_BTN, UiTheme.CLOSE_BTN, UiTheme.RADIUS,
				hovered ? UiTheme.PANEL_ALT : UiTheme.TRACK_TOP,
				hovered ? UiTheme.PANEL_TOP : UiTheme.TRACK_BOTTOM);
		Ui.outline(c, bx, by, UiTheme.CLOSE_BTN, UiTheme.CLOSE_BTN, UiTheme.RADIUS,
				hovered ? UiTheme.ACCENT : UiTheme.BORDER);
		UiIcons.draw(c, UiIcons.Icon.CLOSE, bx + 9, by + 9, 8, hovered ? UiTheme.TEXT : UiTheme.TEXT_2);
	}

	private int searchX(int wx, int ww) {
		return wx + ww - UiTheme.SP_6 - UiTheme.CLOSE_BTN - UiTheme.SP_3 - UiTheme.SEARCH_W;
	}

	private void renderSidebar(UiCanvas c, UiType type, int wx, int wy, int ww, int wh, int mouseX, int mouseY,
			float delta) {
		int px = wx + UiTheme.SP_4;
		int py = wy + UiTheme.HEADER_H - UiTheme.SP_1;
		int pw = UiTheme.SIDEBAR_W - UiTheme.SP_4;
		int ph = wh - UiTheme.HEADER_H + UiTheme.SP_1 - UiTheme.SP_4;

		// The sidebar is a panel set into the shell.
		Ui.surface(c, px, py, pw, ph, UiTheme.RADIUS_LG, UiTheme.PANEL_TOP, UiTheme.PANEL_BOTTOM);
		Ui.outline(c, px, py, pw, ph, UiTheme.RADIUS_LG, UiTheme.BORDER);

		int sx0 = px + UiTheme.SP_2;
		int sw = pw - UiTheme.SP_2 * 2;
		int y = py + UiTheme.SP_5;

		Ui.drawEllipsized(c, type.regular(), "CATEGORIES", sx0 + UiTheme.SP_3, y, sw - UiTheme.SP_5,
				UiTheme.TEXT_3);
		y += UiTheme.SP_6;

		for (int i = 0; i < CATEGORIES.length; i++) {
			Category category = CATEGORIES[i];
			boolean hovered = mouseX >= sx0 && mouseX < sx0 + sw && mouseY >= y && mouseY < y + UiTheme.CATEGORY_H;
			float sel = select[i];
			int rowH = UiTheme.CATEGORY_H;
			int rr = UiTheme.RADIUS;

			if (sel > 0.02f) {
				// The active row: a pink wash with a leading accent bar.
				Ui.surface(c, sx0, y, sw, rowH, rr,
						Ui.withAlpha(UiTheme.ACCENT, Math.round(30 * sel)),
						Ui.withAlpha(UiTheme.ACCENT_DEEP, Math.round(14 * sel)));
				int barH = Math.round((rowH - 16) * sel);
				Ui.surface(c, sx0 + 2, y + (rowH - barH) / 2, 3, barH, 2,
						Ui.withAlpha(UiTheme.ACCENT, Math.round(255 * sel)),
						Ui.withAlpha(UiTheme.ACCENT_DEEP, Math.round(255 * sel)));
			} else if (hovered) {
				Ui.surface(c, sx0, y, sw, rowH, rr,
						Ui.withAlpha(UiTheme.PANEL_ALT, 0xB0),
						Ui.withAlpha(UiTheme.PANEL_TOP, 0x90));
			}

			int iconBox = 16;
			int iconX = sx0 + UiTheme.SP_4;
			int iconY = y + (rowH - iconBox) / 2;
			int resting = Ui.withAlpha(category.accent, 0xB0);
			int iconColor = Ui.lerpColor(resting, UiTheme.ACCENT, Math.max(sel, hovered ? 0.9f : 0f));
			UiIcons.draw(c, CATEGORY_ICONS[i], iconX, iconY, iconBox, iconColor);

			int textX = iconX + iconBox + UiTheme.SP_3;
			UiText medium = type.medium();
			int textY = y + (rowH - medium.lineHeight()) / 2;
			int nameColor = Ui.lerpColor(UiTheme.TEXT_2, UiTheme.TEXT, Math.max(sel * 0.9f, hovered ? 0.7f : 0f));
			Ui.drawEllipsized(c, medium, category.title, textX, textY, sx0 + sw - UiTheme.SP_5 - textX, nameColor);

			int count = cards.get(category).size();
			if (count > 0) {
				UiText regular = type.regular();
				String label = String.valueOf(count);
				int bw = regular.width(label) + 10;
				int bx = sx0 + sw - UiTheme.SP_4 - bw;
				int by = y + (rowH - 14) / 2;
				Ui.surface(c, bx, by, bw, 14, 7,
						Ui.lerpColor(Ui.withAlpha(UiTheme.PANEL_ALT, 0xD0), Ui.withAlpha(UiTheme.ACCENT, 0x59), sel),
						Ui.lerpColor(Ui.withAlpha(UiTheme.PANEL_TOP, 0xD0), Ui.withAlpha(UiTheme.ACCENT_DEEP, 0x33), sel));
				Ui.drawCentered(c, regular, label, bx + bw / 2, by + (14 - regular.lineHeight()) / 2,
						Ui.lerpColor(UiTheme.TEXT_3, UiTheme.TEXT, sel));
			}

			y += rowH + UiTheme.SP_1;
		}

		// A large, very faint blossom fills the space between the categories
		// and the footer, so the panel never looks unfinished.
		// The blossom sits behind the category list like a watermark on the
		// panel, so the sidebar never reads as an empty box.
		UiIcons.draw(c, UiIcons.Icon.BLOSSOM, px + pw / 2 - 48, py + ph / 2 - 48, 96,
				Ui.withAlpha(UiTheme.ACCENT, 0x0D));

		// Theme picker at the foot of the sidebar.
		int pickerH = 26;
		int pickerY = py + ph - UiTheme.SP_8 - pickerH;
		Ui.drawEllipsized(c, type.regular(), "THEME", sx0 + UiTheme.SP_3, pickerY - UiTheme.SP_4,
				sw - UiTheme.SP_5, UiTheme.TEXT_3);
		themePicker.bind(sx0 + UiTheme.SP_2, pickerY, sw - UiTheme.SP_4, pickerH, true);
		themePicker.render(c, type.regular(), mouseX, mouseY, delta);

		// Keybind hint.
		String hint = ClientKeybinds.openClickGui() == null
				? "?"
				: ClientKeybinds.openClickGui().getTranslatedKeyMessage().getString();
		int hintY = py + ph - UiTheme.SP_5;
		int hintX = sx0 + UiTheme.SP_3;
		Ui.drawEllipsized(c, type.regular(), hint + "  to close", hintX, hintY, sw - UiTheme.SP_4, UiTheme.TEXT_3);
		boolean hintHovered = mouseX >= hintX && mouseX < hintX + sw - UiTheme.SP_4
				&& mouseY >= hintY - 2 && mouseY < hintY + type.regular().lineHeight() + 2;
		if (hintHovered) {
			tooltip.show("Esc closes the GUI. Rebind the key in Options - Controls.", hintX, hintY);
		} else {
			tooltip.hide();
		}
	}

	private void renderContent(UiCanvas c, UiType type, int mouseX, int mouseY, float delta) {
		int cx = contentX();
		int top = contentTop();
		int bottom = contentBottom();
		int cw = contentW();
		int viewportH = Math.max(0, bottom - top);

		List<ModuleCard> list = visibleCards();
		boolean showCategory = !search.query().isEmpty();

		// The section header: what is shown and how much of it there is.
		int headerH = SECTION_HEADER_H;
		UiText bold = type.bold();
		String title = showCategory ? "Search results" : CATEGORIES[selected].title;
		Ui.drawEllipsized(c, bold, title, cx, top, cw - 60, UiTheme.TEXT);
		Ui.drawRightAligned(c, type.regular(),
				list.size() + (list.size() == 1 ? " module" : " modules"),
				cx + cw, top + 2, UiTheme.TEXT_3);
		// A delicate line under the section header.
		Ui.fadeLine(c, cx, top + headerH - 6, cw, Ui.withAlpha(UiTheme.BORDER, 0xC0));

		int listTop = top + headerH;
		int listH = Math.max(0, bottom - listTop);

		// Content that moves between pages slides a little.
		float slide = showCategory ? 0f : (1f - switchProgress) * 10f;

		int contentH = 0;
		for (int i = 0; i < list.size(); i++) {
			ModuleCard card = list.get(i);
			card.animate(delta);
			contentH += card.height();
			if (i < list.size() - 1) {
				contentH += UiTheme.CARD_GAP;
			}
		}

		scrollbar.update(listTop, bottom, contentH, delta);
		scrollbar.setRailX(cx + cw + UiTheme.SP_3);

		if (list.isEmpty()) {
			renderEmptyState(c, type, cx + cw / 2, listTop + listH / 2, showCategory);
			return;
		}

		// A very faint blossom in the corner of the workspace. It gives the
		// empty area a purpose without adding any content.
		UiIcons.draw(c, UiIcons.Icon.BLOSSOM, cx + cw - 86, bottom - 94, 86,
				Ui.withAlpha(UiTheme.ACCENT, 0x0F));

		c.pushClip(cx, listTop, cw, listH);
		int y = listTop - (int) Math.round(scrollbar.scroll()) + Math.round(slide);
		for (int i = 0; i < list.size(); i++) {
			ModuleCard card = list.get(i);
			if (y + card.height() >= listTop - 40 && y <= bottom) {
				card.render(c, type, cx, y, cw, mouseX, mouseY, delta, showCategory);
			}
			y += card.height() + UiTheme.CARD_GAP;
		}
		c.popClip();

		scrollbar.render(c);
	}

	/**
	 * The cards to show. The search result is cached and only rebuilt when
	 * the query actually changes, so a frame never allocates a new list.
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

	private void renderEmptyState(UiCanvas c, UiType type, int centerX, int centerY, boolean searchEmpty) {
		UiText regular = type.regular();
		UiText medium = type.medium();
		String title = searchEmpty ? "No matches" : "Nothing here yet";
		String sub = searchEmpty
				? "No module matches your search."
				: "This category has no modules yet.";

		// A blossom in a soft disc, so the empty state still belongs to the
		// theme instead of looking like a missing screen.
		int orb = 46;
		int oy = centerY - 40;
		Ui.surface(c, centerX - orb / 2, oy, orb, orb, orb / 2, UiTheme.PANEL_ALT, UiTheme.PANEL_TOP);
		Ui.outline(c, centerX - orb / 2, oy, orb, orb, orb / 2, UiTheme.BORDER);
		UiIcons.draw(c, searchEmpty ? UiIcons.Icon.SEARCH : UiIcons.Icon.BLOSSOM,
				centerX - 10, oy + 10, 20, UiTheme.TEXT_3);

		Ui.drawCentered(c, medium, title, centerX, oy + orb + 14, UiTheme.TEXT_2);
		Ui.drawCentered(c, regular, sub, centerX, oy + orb + 30, UiTheme.TEXT_3);
	}

	// ----------------------------------------------------------------- input

	/** Handles a click. Returns true when the click was consumed. */
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		double mx = event.x();
		double my = event.y();
		int button = event.button();

		int wx = windowX();
		int wy = windowY();
		int ww = windowWidth();
		int wh = windowHeight();
		if (mx < wx || mx >= wx + ww || my < wy || my >= wy + wh) {
			return true; // swallow clicks outside the window
		}

		// A mouse button bound to the GUI closes it.
		if (ClientKeybinds.openClickGui() != null && ClientKeybinds.openClickGui().matchesMouse(event)) {
			closeRequested = true;
			return true;
		}

		// The theme list floats above everything, so it gets the first refusal.
		if (themePicker.isExpanded() && themePicker.mouseClicked(mx, my)) {
			applyThemeIfChanged();
			return true;
		}

		// Search.
		if (button == 0 && search.contains(mx, my)) {
			if (search.isOnClear(mx, my)) {
				search.clear();
			} else {
				search.setFocused(true);
			}
			return true;
		}
		search.setFocused(false);
		clearCardTextFocus();

		// Close button.
		if (button == 0) {
			int bx = wx + ww - UiTheme.SP_6 - UiTheme.CLOSE_BTN;
			int by = wy + (UiTheme.HEADER_H - UiTheme.CLOSE_BTN) / 2;
			if (mx >= bx && mx < bx + UiTheme.CLOSE_BTN && my >= by && my < by + UiTheme.CLOSE_BTN) {
				closeRequested = true;
				return true;
			}
		}

		// Sidebar rows.
		int px = wx + UiTheme.SP_4;
		int py = wy + UiTheme.HEADER_H - UiTheme.SP_1;
		int pw = UiTheme.SIDEBAR_W - UiTheme.SP_4;
		int sx0 = px + UiTheme.SP_2;
		int sw = pw - UiTheme.SP_2 * 2;
		int rowY = py + UiTheme.SP_5 + UiTheme.SP_6;
		if (mx >= sx0 && mx < sx0 + sw && my >= rowY) {
			int index = (int) ((my - rowY) / (UiTheme.CATEGORY_H + UiTheme.SP_1));
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

		// Scrollbar rail.
		int railX = contentX() + contentW() + UiTheme.SP_3;
		if (mx >= railX - 3 && mx < railX + 8 && my >= contentTop() && my < contentBottom()) {
			scrollbar.pressed(my);
			return true;
		}

		// Cards.
		int cx = contentX();
		int cw = contentW();
		int listTop = contentTop() + 26;
		if (mx >= cx && mx < cx + cw && my >= listTop && my < contentBottom()) {
			List<ModuleCard> list = visibleCards();
			for (int i = list.size() - 1; i >= 0; i--) {
				ModuleCard card = list.get(i);
				if (card.contains(mx, my) && card.mouseClicked(mx, my, button)) {
					return true;
				}
			}
		}
		return true;
	}

	public boolean mouseDragged(double mx, double my) {
		if (scrollbar.isDragging()) {
			scrollbar.dragged(my);
			return true;
		}
		for (Category category : CATEGORIES) {
			for (ModuleCard card : cards.get(category)) {
				card.mouseDragged(mx, my);
			}
		}
		return true;
	}

	public boolean mouseReleased(double mx, double my) {
		scrollbar.released();
		for (Category category : CATEGORIES) {
			for (ModuleCard card : cards.get(category)) {
				card.mouseReleased(mx, my);
			}
		}
		return true;
	}

	public boolean mouseScrolled(double mx, double my, double amount) {
		return scrollbar.onScroll(amount);
	}

	public boolean charTyped(CharacterEvent event) {
		if (search.charTyped(event)) {
			return true;
		}
		for (Category category : CATEGORIES) {
			for (ModuleCard card : cards.get(category)) {
				if (card.charTyped(event)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Handles a key press. The client's own binding is only honoured when no
	 * text field owns the keyboard.
	 */
	public boolean keyPressed(KeyEvent event) {
		if (!hasTextInput() && ClientKeybinds.openClickGui() != null
				&& ClientKeybinds.openClickGui().matches(event)) {
			closeRequested = true;
			return true;
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
		return false;
	}

	/** True while a text field owns the keyboard. */
	public boolean hasTextInput() {
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

	private void clearCardTextFocus() {
		for (Category category : CATEGORIES) {
			for (ModuleCard card : cards.get(category)) {
				card.clearTextInput();
			}
		}
	}

	// ----------------------------------------------------------------- theme

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

	private void applyThemeIfChanged() {
		int index = themePicker.selected();
		if (index == themeIndex) {
			return;
		}
		themeIndex = index;
		List<UiTheme.Palette> palettes = UiTheme.palettes();
		if (index < 0 || index >= palettes.size()) {
			return;
		}
		UiTheme.setPalette(palettes.get(index).id());
		ClientConfig config = ClientConfig.get();
		config.setTheme(palettes.get(index).id());
		ClientConfig.saveNow();
	}
}
