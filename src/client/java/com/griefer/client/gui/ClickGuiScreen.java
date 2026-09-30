package com.griefer.client.gui;

import com.griefer.client.keybind.ClientKeybinds;
import com.griefer.client.module.Category;
import com.griefer.client.module.Module;
import com.griefer.client.module.ModuleManager;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The Griefer GUI: an application-style shell floating over the game.
 *
 * Structure: header (logo, page title, search, close) / sidebar (icon
 * navigation) / content workspace (module cards or a designed empty state).
 * Typography is Manrope via {@link UiFonts}; every color, gap and radius
 * comes from {@link UiTheme}; all drawing goes through {@link Ui}.
 *
 * Lifecycle: the open animation (scale + fade) runs only while this screen
 * exists — closing discards the instance, so nothing animates or listens
 * afterwards. Right Shift opens/closes exactly as before (single
 * edge-triggered listener in the entrypoint).
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

	private final Anim open = new Anim(0f, 14f);
	private final float[] select = new float[CATEGORIES.length];

	private int selected;
	private int lastSelected = -1;
	private float switchProgress = 1f;
	private boolean closing;
	private ModuleCard pressedCard;

	public ClickGuiScreen() {
		super(Component.translatable("griefer.gui.title"));
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
		switchProgress = 1f;
		closing = false;
		search.setFocused(false);
		open.to(0f, 0f);
	}

	/** Keeps layout working across window resizes without rebuilding state. */
	@Override
	public void resize(int width, int height) {
		this.width = width;
		this.height = height;
	}

	// ---------------------------------------------------------------- layout

	private int windowWidth() {
		return Math.max(380, Math.min(520, (int) (this.width * 0.55f)));
	}

	private int windowHeight() {
		return Math.max(230, Math.min(290, (int) (this.height * 0.72f)));
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

	private boolean inWindow(double mx, double my) {
		int wx = windowX();
		int wy = windowY();
		float s = openScale();
		int ww = Math.round(windowWidth() * s);
		int wh = Math.round(windowHeight() * s);
		return mx >= wx && mx < wx + ww && my >= wy && my < wy + wh;
	}

	private boolean inContent(double mx, double my) {
		int wx = windowX();
		int wy = windowY();
		float s = openScale();
		int ww = Math.round(windowWidth() * s);
		int wh = Math.round(windowHeight() * s);
		int cx = wx + Math.round((UiTheme.SIDEBAR_W + 4) * s);
		int top = wy + Math.round((UiTheme.HEADER_H + 10) * s);
		int bottom = wy + wh - Math.round(12 * s);
		return mx >= cx && mx < wx + ww - Math.round(10 * s) && my >= top && my < bottom;
	}

	// ---------------------------------------------------------------- render

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		// Rebuild UI fonts after resource reloads (F3+T / pack switch).
		UiFonts.tick(Minecraft.getInstance());

		// Fade: scrim eases in from transparent; skip the shell while invisible.
		open.to(closing ? 0f : 1f, partialTick);
		g.fill(0, 0, this.width, this.height, Ui.lerpColor(0x00000000, UiTheme.SCRIM, open.value()));
		if (open.value() <= 0.03f) {
			if (closing) {
				finishClose();
			}
			return;
		}
		for (int i = 0; i < select.length; i++) {
			select[i] += ((i == selected ? 1f : 0f) - select[i]) * (1f - (float) Math.exp(-16f * partialTick));
		}
		if (selected != lastSelected) {
			lastSelected = selected;
			switchProgress = 0f;
		}
		switchProgress += (1f - switchProgress) * (1f - (float) Math.exp(-14f * partialTick));

		int wx = windowX();
		int wy = windowY();
		float s = openScale();
		int ww = Math.round(windowWidth() * s);
		int wh = Math.round(windowHeight() * s);

		Ui.shadow(g, wx, wy, ww, wh, UiTheme.RADIUS_LG);
		Ui.roundRect(g, wx, wy, ww, wh, UiTheme.RADIUS_LG, UiTheme.BG);
		Ui.roundOutline(g, wx, wy, ww, wh, UiTheme.RADIUS_LG, UiTheme.LINE);

		renderHeader(g, wx, wy, ww, mouseX, mouseY, partialTick);
		renderSidebar(g, wx, wy, wh, s, mouseX, mouseY, partialTick);
		renderContent(g, wx, wy, ww, wh, s, mouseX, mouseY, partialTick);
	}

	private void renderHeader(GuiGraphics g, int wx, int wy, int ww, int mouseX, int mouseY, float partialTick) {
		// Logo chip + client name
		int chip = UiTheme.SP_4 + 2; // 14px
		Ui.roundRect(g, wx + UiTheme.SP_5, wy + (UiTheme.HEADER_H - chip) / 2, chip, chip, 4, UiTheme.ACCENT);
		String initial = "G";
		net.minecraft.client.gui.Font bold = UiFonts.bold();
		g.drawString(bold, initial, wx + UiTheme.SP_5 + (chip - bold.width(initial)) / 2,
				wy + (UiTheme.HEADER_H - bold.lineHeight) / 2 + 1, UiTheme.ON_ACCENT, false);

		net.minecraft.client.gui.Font name = UiFonts.bold();
		int nx = wx + UiTheme.SP_5 + chip + UiTheme.SP_3;
		int ny = wy + (UiTheme.HEADER_H - name.lineHeight) / 2;
		g.drawString(name, "Griefer", nx, ny, UiTheme.TEXT, false);

		// Page title (current category or Search)
		net.minecraft.client.gui.Font regular = UiFonts.regular();
		String page = search.query().isEmpty() ? CATEGORIES[selected].title : "Search";
		int pageX = nx + name.width("Griefer") + UiTheme.SP_4;
		int searchX = wx + ww - UiTheme.SP_5 - UiTheme.SEARCH_W - UiTheme.SP_4 - 16;
		if (searchX - pageX > UiTheme.SP_4) {
			Ui.drawEllipsized(g, regular, page, pageX, ny + 1, searchX - pageX - UiTheme.SP_4, UiTheme.TEXT_3);
		}

		// Search field, right-aligned before the close button
		int sx = wx + ww - UiTheme.SP_5 - UiTheme.SEARCH_W - UiTheme.SP_4 - 16;
		int sy = wy + (UiTheme.HEADER_H - UiTheme.SEARCH_H) / 2;
		search.bind(sx, sy, UiTheme.SEARCH_W, UiTheme.SEARCH_H);
		search.render(g, UiFonts.regular(), mouseX, mouseY, partialTick);

		// Close button
		int bx = wx + ww - UiTheme.SP_4 - 16;
		int by = wy + (UiTheme.HEADER_H - 16) / 2;
		boolean hovered = mouseX >= bx && mouseX < bx + 16 && mouseY >= by && mouseY < by + 16;
		if (hovered) {
			Ui.roundRect(g, bx, by, 16, 16, UiTheme.RADIUS, UiTheme.SURFACE_3);
		}
		UiIcons.draw(g, UiIcons.Icon.CLOSE, bx + 4, by + 4, 8, hovered ? UiTheme.TEXT : UiTheme.TEXT_3);
	}

	private void renderSidebar(GuiGraphics g, int wx, int wy, int wh, float s, int mouseX, int mouseY, float partialTick) {
		int sx = wx + Math.round(UiTheme.SP_3 * s);
		int sw = Math.round((UiTheme.SIDEBAR_W - UiTheme.SP_3) * s);
		int y = wy + Math.round((UiTheme.HEADER_H + UiTheme.SP_3) * s);
		int rowH = Math.round(26 * s);
		int gap = Math.round(2 * s);

		for (int i = 0; i < CATEGORIES.length; i++) {
			Category category = CATEGORIES[i];
			boolean hovered = mouseX >= sx && mouseX < sx + sw && mouseY >= y && mouseY < y + rowH;
			float sel = select[i];

			if (sel > 0.02f) {
				// Selected: subtle tinted pill + small accent indicator
				Ui.roundRect(g, sx, y, sw, rowH, UiTheme.RADIUS, Ui.lerpColor(UiTheme.BG, UiTheme.SURFACE_2, sel));
				int barH = Math.round(14 * sel * s);
				Ui.roundRect(g, sx + 1, y + (rowH - barH) / 2, 2, barH, 1, Ui.lerpColor(0x00000000, category.accent, sel));
			} else if (hovered) {
				Ui.roundRect(g, sx, y, sw, rowH, UiTheme.RADIUS, UiTheme.SURFACE_2);
			}

			int iconBox = Math.round(14 * s);
			int textX = sx + Math.round(UiTheme.SP_4 * s) + iconBox + Math.round(UiTheme.SP_2 * s);
			int textY = y + (rowH - UiFonts.medium().lineHeight) / 2;
			int iconColor = sel > 0.5f ? UiTheme.TEXT : UiTheme.TEXT_2;
			if (hovered && sel <= 0.02f) {
				iconColor = UiTheme.TEXT;
			}
			UiIcons.draw(g, CATEGORY_ICONS[i], sx + Math.round(UiTheme.SP_4 * s), y + (rowH - iconBox) / 2, iconBox,
					Ui.lerpColor(UiTheme.TEXT_2, iconColor, Math.max(sel, hovered ? 1f : 0f)));

			int nameColor = Ui.lerpColor(UiTheme.TEXT_2, UiTheme.TEXT, Math.max(sel, hovered ? 0.8f : 0f));
			Ui.drawEllipsized(g, UiFonts.medium(), category.title, textX, textY,
					sx + sw - UiTheme.SP_2 - textX, nameColor);

			// Count badge only when meaningful
			int count = cards.get(category).size();
			if (count > 0) {
				String label = String.valueOf(count);
				Ui.drawRightAligned(g, UiFonts.regular(), label, sx + sw - UiTheme.SP_3, textY,
						Ui.lerpColor(UiTheme.TEXT_3, UiTheme.TEXT_2, sel));
			}

			y += rowH + gap;
		}

		// Small keybind hint at the sidebar foot — secondary, unobtrusive
		String hint = ClientKeybinds.openClickGui().getTranslatedKeyMessage().getString();
		Ui.drawEllipsized(g, UiFonts.regular(), hint + " to close", sx + UiTheme.SP_2,
				wy + wh - Math.round(16 * s), sw - UiTheme.SP_2, UiTheme.HINT);
	}

	private void renderContent(GuiGraphics g, int wx, int wy, int ww, int wh, float s, int mouseX, int mouseY, float partialTick) {
		int cx = wx + Math.round((UiTheme.SIDEBAR_W + 4) * s);
		int top = wy + Math.round((UiTheme.HEADER_H + 10) * s);
		int bottom = wy + wh - Math.round(12 * s);
		int cw = wx + ww - Math.round(10 * s) - cx;
		int viewportH = Math.max(0, bottom - top);

		List<ModuleCard> list = visibleCards();
		boolean showCategory = !search.query().isEmpty();

		// Category-switch transition: content fades/slides in
		if (!showCategory && switchProgress < 0.999f) {
			float a = Math.max(0f, switchProgress);
			top += Math.round((1f - a) * 6f);
		}

		// Measure with current animated heights
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
		scrollbar.setRailX(cx + cw + UiTheme.SP_1);

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

	private List<ModuleCard> visibleCards() {
		String q = search.query().trim().toLowerCase(Locale.ROOT);
		if (!q.isEmpty()) {
			List<ModuleCard> out = new ArrayList<>();
			for (Category category : CATEGORIES) {
				for (ModuleCard card : cards.get(category)) {
					if (card.title().toLowerCase(Locale.ROOT).contains(q)
							|| card.descriptionText().toLowerCase(Locale.ROOT).contains(q)
							|| card.module().id().replace('_', ' ').contains(q)) {
						out.add(card);
					}
				}
			}
			return out;
		}
		return cards.get(CATEGORIES[selected]);
	}

	private void renderEmptyState(GuiGraphics g, int centerX, int centerY, boolean searchEmpty) {
		net.minecraft.client.gui.Font regular = UiFonts.regular();
		net.minecraft.client.gui.Font medium = UiFonts.medium();
		String title = searchEmpty ? "No matches" : "Nothing here yet";
		String sub = searchEmpty
				? "No modules match your search."
				: "This category currently has no modules.";
		int ty = centerY - 10;
		g.drawString(medium, title, centerX - medium.width(title) / 2, ty, UiTheme.TEXT_2, false);
		g.drawString(regular, sub, centerX - regular.width(sub) / 2, ty + 13, UiTheme.TEXT_3, false);
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

		// Search field first (it sits in the header)
		if (button == 0 && search.contains(mx, my)) {
			search.setFocused(true);
			return true;
		}
		search.setFocused(false);

		int wx = windowX();
		int wy = windowY();
		float s = openScale();
		int ww = Math.round(windowWidth() * s);

		// Close button
		if (button == 0) {
			int bx = wx + ww - UiTheme.SP_4 - 16;
			int by = wy + (UiTheme.HEADER_H - 16) / 2;
			if (mx >= bx && mx < bx + 16 && my >= by && my < by + 16) {
				onClose();
				return true;
			}
		}

		// Sidebar rows
		int sx = wx + Math.round(UiTheme.SP_3 * s);
		int sw = Math.round((UiTheme.SIDEBAR_W - UiTheme.SP_3) * s);
		int rowY = wy + Math.round((UiTheme.HEADER_H + UiTheme.SP_3) * s);
		int rowH = Math.round(26 * s);
		int gap = Math.round(2 * s);
		if (mx >= sx && mx < sx + sw) {
			int index = (int) ((my - rowY) / (rowH + gap));
			if (index >= 0 && index < CATEGORIES.length && my >= rowY) {
				selected = index;
				search.clear();
				return true;
			}
		}

		// Content area: cards
		if (inContent(mx, my)) {
			List<ModuleCard> list = visibleCards();
			for (int i = list.size() - 1; i >= 0; i--) {
				ModuleCard card = list.get(i);
				if (card.contains(mx, my) && card.mouseClicked(mx, my, button)) {
					pressedCard = card;
					return true;
				}
			}
			// Scrollbar rail
			int railX = contentRailX();
			if (mx >= railX - 2 && mx < railX + 5) {
				scrollbar.pressed(my);
				return true;
			}
		}
		return true;
	}

	private int contentRailX() {
		int wx = windowX();
		float s = openScale();
		int ww = Math.round(windowWidth() * s);
		int cx = wx + Math.round((UiTheme.SIDEBAR_W + 4) * s);
		int cw = wx + ww - Math.round(10 * s) - cx;
		return cx + cw + UiTheme.SP_1;
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
		return super.charTyped(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		// Unchanged behavior: the registered (rebindable) keybind closes the GUI.
		if (ClientKeybinds.openClickGui().matches(event)) {
			requestClose();
			return true;
		}
		if (search.keyPressed(event)) {
			return true;
		}
		return super.keyPressed(event);
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
		super.onClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
