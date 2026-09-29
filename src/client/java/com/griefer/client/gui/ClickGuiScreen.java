package com.griefer.client.gui;

import com.griefer.client.keybind.ClientKeybinds;
import com.griefer.client.module.Category;
import com.griefer.client.module.Module;
import com.griefer.client.module.ModuleManager;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The Griefer ClickGUI: a single centered window with a sidebar for category
 * navigation and a scrollable list of module cards on the right.
 *
 * Everything is computed per frame from {@code width}/{@code height}, so GUI
 * scaling and window resizes need no relayout. All animation state lives in
 * this screen instance — it is discarded on close, so nothing persists or
 * ticks after the GUI is gone.
 *
 * Input: Right Shift (the registered, rebindable keybind) opens and closes
 * the GUI; mouse coordinates arrive pre-scaled from vanilla so all hit
 * targets below use the same coordinate space as rendering.
 */
public class ClickGuiScreen extends Screen {
	private static final int HEADER_H = 20;
	private static final int SIDEBAR_ROW_H = 24;
	private static final Category[] CATEGORIES = Category.values();

	private final Map<Category, List<ModuleCard>> cards = new EnumMap<>(Category.class);
	private final UiScrollbar scrollbar = new UiScrollbar();

	private final Anim open = new Anim(0f, 20f);
	private final float[] select = new float[Category.values().length];

	private int selected;
	private ModuleCard pressedCard;
	private boolean sidebarHover = false;
	private int sidebarHoverIndex = -1;

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
		return Math.max(300, Math.min(540, (int) (this.width * 0.6f)));
	}

	private int windowHeight() {
		return Math.max(180, Math.min(250, (int) (this.height * 0.72f)));
	}

	private int windowX() {
		return (this.width - windowWidth()) / 2;
	}

	private int baseWindowY() {
		return (this.height - windowHeight()) / 2;
	}

	private int windowY() {
		// Slide-up + settle on open.
		return Math.max(0, baseWindowY() + Math.round(8 * (1f - open.value())));
	}

	private int contentX() {
		return windowX() + UiTheme.SIDEBAR_W;
	}

	private int contentTop() {
		return windowY() + HEADER_H + UiTheme.CONTENT_PAD;
	}

	private int contentBottom() {
		return windowY() + windowHeight() - UiTheme.CONTENT_PAD;
	}

	private int contentWidth() {
		return windowWidth() - UiTheme.SIDEBAR_W - UiTheme.CONTENT_PAD * 2;
	}

	private boolean inWindow(double mx, double my) {
		int wx = windowX();
		int wy = windowY();
		return mx >= wx && mx < wx + windowWidth() && my >= wy && my < wy + windowHeight();
	}

	// ---------------------------------------------------------------- render

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		g.fill(0, 0, this.width, this.height, UiTheme.SCRIM);

		open.to(1f, partialTick);
		// selection easing
		for (int i = 0; i < select.length; i++) {
			select[i] += ((i == selected ? 1f : 0f) - select[i]) * (1f - (float) Math.exp(-16f * partialTick));
		}

		int wx = windowX();
		int wy = windowY();
		int ww = windowWidth();
		int wh = windowHeight();

		Ui.shadow(g, wx, wy, ww, wh, UiTheme.RADIUS);
		Ui.roundRect(g, wx, wy, ww, wh, UiTheme.RADIUS, UiTheme.SURFACE_0);
		Ui.roundOutline(g, wx, wy, ww, wh, UiTheme.RADIUS, UiTheme.LINE);

		renderHeader(g, wx, wy, ww);
		renderSidebar(g, wx, wy, wh, mouseX, mouseY, partialTick);
		renderContent(g, mouseX, mouseY, partialTick);
	}

	private void renderHeader(GuiGraphics g, int wx, int wy, int ww) {
		g.drawString(this.font, "Griefer", wx + UiTheme.CONTENT_PAD, wy + (HEADER_H - 8) / 2, UiTheme.TEXT, false);
		String hint = ClientKeybinds.openClickGui().getTranslatedKeyMessage().getString() + " to close";
		Ui.drawRightAligned(g, this.font, hint, wx + ww - UiTheme.CONTENT_PAD, wy + (HEADER_H - 8) / 2, UiTheme.TEXT_FAINT);
		g.fill(wx + 1, wy + HEADER_H, wx + ww - 1, wy + HEADER_H + 1, UiTheme.LINE);
	}

	private void renderSidebar(GuiGraphics g, int wx, int wy, int wh, int mouseX, int mouseY, float partialTick) {
		// Sidebar surface (inset so the window outline stays crisp)
		g.fill(wx + 1, wy + HEADER_H + 1, wx + UiTheme.SIDEBAR_W, wy + wh - 1, UiTheme.SURFACE_1);
		g.fill(wx + UiTheme.SIDEBAR_W, wy + HEADER_H + 1, wx + UiTheme.SIDEBAR_W + 1, wy + wh - 1, UiTheme.LINE);

		sidebarHover = false;
		sidebarHoverIndex = -1;

		int rowY = wy + HEADER_H + UiTheme.SP_3;
		Category[] values = Category.values();
		for (int i = 0; i < values.length; i++) {
			Category category = values[i];
			boolean hovered = mouseX >= wx + 1 && mouseX < wx + UiTheme.SIDEBAR_W
					&& mouseY >= rowY && mouseY < rowY + SIDEBAR_ROW_H;
			if (hovered) {
				sidebarHover = true;
				sidebarHoverIndex = i;
			}

			float sel = select[i];
			int rowBg = Ui.lerpColor(UiTheme.SURFACE_1, UiTheme.SURFACE_2, Math.max(sel, hovered ? 0.45f : 0f));
			if (sel > 0.01f) {
				Ui.roundRect(g, wx + UiTheme.SP_2, rowY, UiTheme.SIDEBAR_W - UiTheme.SP_2 * 2, SIDEBAR_ROW_H, UiTheme.RADIUS_SM, rowBg);
				int barH = Math.round((SIDEBAR_ROW_H - 8) * sel);
				g.fill(wx + UiTheme.SP_2, rowY + (SIDEBAR_ROW_H - barH) / 2, wx + UiTheme.SP_2 + 2, rowY + (SIDEBAR_ROW_H - barH) / 2 + barH, category.accent);
			} else if (hovered) {
				Ui.roundRect(g, wx + UiTheme.SP_2, rowY, UiTheme.SIDEBAR_W - UiTheme.SP_2 * 2, SIDEBAR_ROW_H, UiTheme.RADIUS_SM, rowBg);
			}

			// Category dot + name
			int textX = wx + UiTheme.SP_5 + 4;
			int textY = rowY + (SIDEBAR_ROW_H - 8) / 2;
			g.fill(textX, textY + 3, textX + 3, textY + 6, category.accent);
			// Ellipsize so long category names can never collide with the badge
			int labelMax = UiTheme.SIDEBAR_W - (textX + 8 - wx) - UiTheme.SIDEBAR_W / 4;
			String label = category.title;
			while (label.length() > 1 && this.font.width(label) > labelMax) {
				label = label.substring(0, label.length() - 1);
			}
			if (!label.equals(category.title)) {
				label = label + "…";
			}
			g.drawString(this.font, label, textX + 8, textY,
					Ui.lerpColor(UiTheme.TEXT_SUB, UiTheme.TEXT, Math.max(sel, hovered ? 0.6f : 0f)), false);

			// Module count badge
			int count = cards.get(category).size();
			if (count > 0) {
				String countLabel = String.valueOf(count);
				Ui.drawRightAligned(g, this.font, countLabel, wx + UiTheme.SIDEBAR_W - UiTheme.SP_4, textY, UiTheme.TEXT_FAINT);
			}

			rowY += SIDEBAR_ROW_H;
		}
	}

	private void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		List<ModuleCard> list = cards.get(CATEGORIES[selected]);
		int cx = contentX();
		int cw = contentWidth();
		int top = contentTop();
		int bottom = contentBottom();
		int viewportH = Math.max(0, bottom - top);

		// Advance every card's animation exactly once, then measure the real
		// laid-out height so scroll bounds always match what is rendered.
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
		scrollbar.setRailX(cx + cw + UiTheme.SP_2);

		if (list.isEmpty()) {
			renderEmptyState(g, cx + cw / 2, top + viewportH / 2);
			return;
		}

		boolean clipped = Ui.beginClip(g, cx, top, cw, viewportH);
		double scroll = scrollbar.scroll();
		int y = top - (int) Math.round(scroll);
		for (int i = 0; i < list.size(); i++) {
			ModuleCard card = list.get(i);
			if (y + card.height() >= top && y <= bottom) {
				card.render(g, this.font, cx, y, cw, mouseX, mouseY, partialTick);
			}
			y += card.height() + UiTheme.CARD_GAP;
		}
		if (clipped) {
			Ui.endClip(g);
		}

		scrollbar.render(g);
	}

	private void renderEmptyState(GuiGraphics g, int centerX, int centerY) {
		// Small diamond mark
		int dy = centerY - 22;
		for (int i = 0; i <= 3; i++) {
			g.fill(centerX - i, dy + i, centerX - i + 1, dy + i + 1, UiTheme.TEXT_FAINT);
			g.fill(centerX + i, dy + i, centerX + i + 1, dy + i + 1, UiTheme.TEXT_FAINT);
		}
		for (int i = 0; i <= 2; i++) {
			g.fill(centerX - (3 - i), dy + 4 + i, centerX - (3 - i) + 1, dy + 4 + i + 1, UiTheme.TEXT_FAINT);
			g.fill(centerX + (3 - i), dy + 4 + i, centerX + (3 - i) + 1, dy + 4 + i + 1, UiTheme.TEXT_FAINT);
		}
		g.drawCenteredString(this.font, "No modules yet", centerX, centerY - 4, UiTheme.TEXT_FAINT);
		g.drawCenteredString(this.font, "Modules added to this category will appear here",
				centerX, centerY + 8, 0xFF3A424C);
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		double mx = event.x();
		double my = event.y();
		int button = event.button();

		if (!inWindow(mx, my) || button != 0) {
			return true; // swallow everything while the GUI is open
		}

		// Sidebar rows
		int wx = windowX();
		int wy = windowY();
		if (mx >= wx + 1 && mx < wx + UiTheme.SIDEBAR_W && my >= wy + HEADER_H && my < wy + windowHeight()) {
			int index = (int) ((my - (wy + HEADER_H + UiTheme.SP_3)) / SIDEBAR_ROW_H);
			if (index >= 0 && index < CATEGORIES.length) {
				selected = index;
			}
			return true;
		}

		// Module cards (reverse order; card bounds already include settings).
		// Only cards inside the content viewport are hit-testable — cards
		// scrolled out of view keep stale bounds and must never swallow clicks.
		int top = contentTop();
		int bottom = contentBottom();
		if (my >= top && my < bottom) {
			List<ModuleCard> list = cards.get(CATEGORIES[selected]);
			for (int i = list.size() - 1; i >= 0; i--) {
				ModuleCard card = list.get(i);
				if (card.contains(mx, my) && card.mouseClicked(mx, my, button)) {
					pressedCard = card;
					return true;
				}
			}
		}

		// Scrollbar rail
		int railX = contentX() + contentWidth() + UiTheme.SP_2;
		if (mx >= railX - 2 && mx < railX + 5) {
			scrollbar.pressed(my);
			return true;
		}
		return true;
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
	public boolean keyPressed(KeyEvent event) {
		if (ClientKeybinds.openClickGui().matches(event)) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		pressedCard = null;
		scrollbar.released();
		super.onClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
