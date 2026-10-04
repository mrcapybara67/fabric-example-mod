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
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The Griefer GUI: a floating pane of liquid glass over the game.
 *
 * Structure: shell (accent bloom, drop shadow, glass body, drifting specular
 * sheen) / header (wordmark, page title, search, close) / sidebar (icon
 * navigation in its own glass column) / content workspace (module cards or a
 * designed empty state).
 *
 * Typography is Inter via {@link UiFonts}; every color, gap and radius comes
 * from {@link UiTheme}; all drawing goes through {@link Ui}.
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
		return Math.max(440, Math.min(620, (int) (this.width * 0.58f)));
	}

	private int windowHeight() {
		return Math.max(300, Math.min(400, (int) (this.height * 0.76f)));
	}

	private float openScale() {
		float t = open.value();
		return 0.955f + 0.045f * t;
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
		return shellY() + sy(UiTheme.HEADER_H + UiTheme.SP_4);
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
		// Fade: scrim eases in from transparent; skip the shell while invisible.
		open.to(closing ? 0f : 1f, partialTick);
		float fade = open.value();
		g.fill(0, 0, this.width, this.height, Ui.lerpColor(0x00000000, UiTheme.SCRIM, fade));
		if (fade <= 0.03f) {
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

		int wx = shellX();
		int wy = shellY();
		int ww = shellW();
		int wh = shellH();
		int r = UiTheme.RADIUS_XL;

		// Accent bloom under the shell, as if the glass glows from within.
		g.fill(wx - 6, wy - 4, wx + ww + 6, wy + wh + 16,
				Ui.lerpColor(0x00000000, UiTheme.ACCENT_GLOW, fade * 0.85f));

		Ui.glassShadow(g, wx, wy, ww, wh, r);
		Ui.glassFill(g, wx, wy, ww, wh, r, UiTheme.SHELL_TOP, UiTheme.SHELL_BOTTOM);
		Ui.sheen(g, wx, wy, ww, wh, r, sheenPhase());
		Ui.glassEdge(g, wx, wy, ww, wh, r, UiTheme.EDGE_TOP, UiTheme.EDGE_BOTTOM);
		Ui.innerBevel(g, wx, wy, ww, wh, r, UiTheme.INNER_TOP, UiTheme.INNER_BOTTOM);

		renderHeader(g, wx, wy, ww, mouseX, mouseY, partialTick);
		renderSidebar(g, mouseX, mouseY, partialTick);
		renderContent(g, mouseX, mouseY, partialTick);
	}

	/** Slow drift of the specular band, so the glass never looks static. */
	private float sheenPhase() {
		double t = (System.currentTimeMillis() % 9000L) / 9000.0;
		return (float) (0.5 + 0.5 * Math.sin(t * Math.PI * 2.0));
	}

	private void renderHeader(GuiGraphics g, int wx, int wy, int ww, int mouseX, int mouseY, float partialTick) {
		int cy = wy + (UiTheme.HEADER_H - 18) / 2;
		int chipX = wx + sx(UiTheme.SP_5);

		// Wordmark chip: a small glass orb with the accent glowing inside it.
		int chip = 18;
		Ui.roundRect(g, chipX, cy, chip, chip, 6, 0x66000000);
		Ui.glassFill(g, chipX, cy, chip, chip, 6, UiTheme.ACCENT, UiTheme.ACCENT_DIM);
		Ui.glassEdge(g, chipX, cy, chip, chip, 6, 0xB4FFFFFF, 0x3AFFFFFF);
		Ui.innerBevel(g, chipX, cy, chip, chip, 6, 0x80FFFFFF, 0x33000000);

		net.minecraft.client.gui.Font bold = UiFonts.bold();
		String initial = "G";
		Ui.drawCentered(g, bold, initial, chipX + chip / 2, cy + (chip - bold.lineHeight) / 2 + 1, UiTheme.ON_ACCENT);

		// Wordmark + page title
		int nx = chipX + chip + sx(UiTheme.SP_3);
		int ny = wy + (UiTheme.HEADER_H - bold.lineHeight) / 2;
		net.minecraft.client.gui.Font name = UiFonts.bold();
		g.drawString(name, "Griefer", nx, ny, UiTheme.TEXT, false);

		int fieldX = searchX();
		int pageX = nx + sx(name.width("Griefer") + UiTheme.SP_4);
		if (fieldX - pageX > sx(UiTheme.SP_5)) {
			String page = search.query().isEmpty() ? CATEGORIES[selected].title : "Search";
			// Small accent dot separating wordmark from page.
			g.fill(pageX - sx(UiTheme.SP_2), ny + name.lineHeight / 2 - 1, pageX - sx(UiTheme.SP_2) + 1,
					ny + name.lineHeight / 2, UiTheme.TEXT_3);
			Ui.drawEllipsized(g, UiFonts.medium(), page, pageX, ny + 1, fieldX - pageX - sx(UiTheme.SP_3),
					UiTheme.TEXT_2);
		}

		// Glass search field
		search.bind(fieldX, wy + (UiTheme.HEADER_H - UiTheme.SEARCH_H) / 2, UiTheme.SEARCH_W, UiTheme.SEARCH_H);
		search.render(g, UiFonts.regular(), mouseX, mouseY, partialTick);

		// Close button
		int bx = wx + ww - sx(UiTheme.SP_5) - UiTheme.CLOSE_BTN;
		int by = wy + (UiTheme.HEADER_H - UiTheme.CLOSE_BTN) / 2;
		boolean hovered = mouseX >= bx && mouseX < bx + UiTheme.CLOSE_BTN
				&& mouseY >= by && mouseY < by + UiTheme.CLOSE_BTN;
		Ui.glassFill(g, bx, by, UiTheme.CLOSE_BTN, UiTheme.CLOSE_BTN, UiTheme.RADIUS,
				hovered ? 0x8C2B3646 : 0x661C2431,
				hovered ? 0xA61B2532 : 0x80141B25);
		Ui.glassEdge(g, bx, by, UiTheme.CLOSE_BTN, UiTheme.CLOSE_BTN, UiTheme.RADIUS,
				hovered ? 0x73FFFFFF : 0x33FFFFFF, 0x14FFFFFF);
		UiIcons.draw(g, UiIcons.Icon.CLOSE, bx + 9, by + 9, 8, hovered ? UiTheme.TEXT : UiTheme.TEXT_2);
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

		// The sidebar is its own glass column, set into the shell.
		Ui.glassFill(g, px, py, pw, ph, UiTheme.RADIUS_LG, UiTheme.PANEL_TOP, UiTheme.PANEL_BOTTOM);
		Ui.glassEdge(g, px, py, pw, ph, UiTheme.RADIUS_LG, UiTheme.EDGE_TOP, UiTheme.EDGE_BOTTOM);

		int sx0 = px + sx(UiTheme.SP_2);
		int sw = pw - sx(UiTheme.SP_2) * 2;
		int y = py + sy(UiTheme.SP_3);
		int rowH = sy(30);
		int gap = sy(3);

		for (int i = 0; i < CATEGORIES.length; i++) {
			Category category = CATEGORIES[i];
			boolean hovered = mouseX >= sx0 && mouseX < sx0 + sw && mouseY >= y && mouseY < y + rowH;
			float sel = select[i];
			int rr = UiTheme.RADIUS;

			// Selected row: accent-tinted glass with a leading accent bar.
			if (sel > 0.02f) {
				int top = Ui.lerpColor(0x00000000, Ui.withAlpha(UiTheme.ACCENT, 0x38), sel);
				int bot = Ui.lerpColor(0x00000000, Ui.withAlpha(UiTheme.ACCENT, 0x12), sel);
				Ui.glassFill(g, sx0, y, sw, rowH, rr, top, bot);
				Ui.glassEdge(g, sx0, y, sw, rowH, rr,
						Ui.lerpColor(0x00000000, 0x66FFFFFF, sel),
						Ui.lerpColor(0x00000000, 0x1AFFFFFF, sel));
				int barH = Math.round((rowH - 12) * sel);
				Ui.glassFill(g, sx0 + 2, y + (rowH - barH) / 2, 3, barH, 2,
						Ui.withAlpha(UiTheme.ACCENT, Math.round(255 * sel)),
						Ui.withAlpha(UiTheme.ACCENT_DIM, Math.round(255 * sel)));
			} else if (hovered) {
				Ui.glassFill(g, sx0, y, sw, rowH, rr, 0x59FFFFFF, 0x2EFFFFFF);
				Ui.glassEdge(g, sx0, y, sw, rowH, rr, 0x40FFFFFF, 0x12FFFFFF);
			}

			int iconBox = sy(15);
			int textX = sx0 + sx(UiTheme.SP_3) + iconBox + sx(UiTheme.SP_3);
			int textY = y + (rowH - UiFonts.medium().lineHeight) / 2;
			int iconColor = Ui.lerpColor(UiTheme.TEXT_3, UiTheme.TEXT, Math.max(sel, hovered ? 1f : 0f));
			UiIcons.draw(g, CATEGORY_ICONS[i], sx0 + sx(UiTheme.SP_3), y + (rowH - iconBox) / 2, iconBox, iconColor);

			int nameColor = Ui.lerpColor(UiTheme.TEXT_2, UiTheme.TEXT, Math.max(sel, hovered ? 0.8f : 0f));
			Ui.drawEllipsized(g, UiFonts.medium(), category.title, textX, textY,
					sx0 + sw - sx(UiTheme.SP_3) - textX, nameColor);

			// Count badge only when meaningful
			int count = cards.get(category).size();
			if (count > 0) {
				String label = String.valueOf(count);
				net.minecraft.client.gui.Font f = UiFonts.regular();
				int bw = f.width(label) + sx(8);
				int bx = sx0 + sw - sx(UiTheme.SP_3) - bw;
				int by = y + (rowH - 12) / 2;
				Ui.glassFill(g, bx, by, bw, 12, 6,
						Ui.lerpColor(0x4DFFFFFF, Ui.withAlpha(UiTheme.ACCENT, 0x59), sel),
						Ui.lerpColor(0x33FFFFFF, Ui.withAlpha(UiTheme.ACCENT, 0x29), sel));
				Ui.drawCentered(g, f, label, bx + bw / 2, by + (12 - f.lineHeight) / 2,
						Ui.lerpColor(UiTheme.TEXT_3, UiTheme.TEXT, sel));
			}

			y += rowH + gap;
		}

		// Keybind hint at the foot of the sidebar.
		String hint = ClientKeybinds.openClickGui().getTranslatedKeyMessage().getString();
		Ui.drawEllipsized(g, UiFonts.regular(), hint + " to close", sx0 + sx(UiTheme.SP_2),
				py + ph - sy(18), sw - sx(UiTheme.SP_2), UiTheme.HINT);
	}

	private void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		int cx = contentX();
		int top = contentTop();
		int bottom = contentBottom();
		int cw = contentW();
		int viewportH = Math.max(0, bottom - top);

		List<ModuleCard> list = visibleCards();
		boolean showCategory = !search.query().isEmpty();

		// Category-switch transition: content fades/slides in
		if (!showCategory && switchProgress < 0.999f) {
			top += sy(Math.round((1f - switchProgress) * 8f));
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

		// A small glass orb, so the empty state still belongs to the material.
		int orb = 30;
		int oy = centerY - 34;
		Ui.glassFill(g, centerX - orb / 2, oy, orb, orb, orb / 2, 0x40FFFFFF, 0x14FFFFFF);
		Ui.glassEdge(g, centerX - orb / 2, oy, orb, orb, orb / 2, 0x59FFFFFF, 0x14FFFFFF);
		Ui.innerBevel(g, centerX - orb / 2, oy, orb, orb, orb / 2, 0x33FFFFFF, 0x1F000000);
		UiIcons.draw(g, searchEmpty ? UiIcons.Icon.SEARCH : UiIcons.Icon.DONUT,
				centerX - 7, oy + 7, 14, UiTheme.TEXT_3);

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

		// Search field first (it sits in the header)
		if (button == 0 && search.contains(mx, my)) {
			if (search.isOnClear(mx, my)) {
				search.clear();
			} else {
				search.setFocused(true);
			}
			return true;
		}
		search.setFocused(false);

		int wx = shellX();
		int wy = shellY();

		// Close button
		if (button == 0) {
			int bx = closeX();
			int by = wy + (UiTheme.HEADER_H - UiTheme.CLOSE_BTN) / 2;
			if (mx >= bx && mx < bx + UiTheme.CLOSE_BTN && my >= by && my < by + UiTheme.CLOSE_BTN) {
				onClose();
				return true;
			}
		}

		// Sidebar rows
		int px = wx + sx(UiTheme.SP_3);
		int py = wy + sy(UiTheme.HEADER_H) - sy(UiTheme.SP_1);
		int pw = sx(UiTheme.SIDEBAR_W);
		int sx0 = px + sx(UiTheme.SP_2);
		int sw = pw - sx(UiTheme.SP_2) * 2;
		int rowY = py + sy(UiTheme.SP_3);
		int rowH = sy(30);
		int gap = sy(3);
		if (mx >= sx0 && mx < sx0 + sw) {
			int index = (int) ((my - rowY) / (rowH + gap));
			if (index >= 0 && index < CATEGORIES.length && my >= rowY) {
				selected = index;
				search.clear();
				return true;
			}
		}

		// Scrollbar rail first: it sits in the gutter, outside the card viewport.
		int railX = contentRailX();
		if (mx >= railX - 3 && mx < railX + 8 && my >= contentTop() && my < contentBottom()) {
			scrollbar.pressed(my);
			return true;
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
