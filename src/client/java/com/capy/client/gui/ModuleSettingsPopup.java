package com.capy.client.gui;

/**
 * Animated settings popup for one module.
 *
 * <p>Right-clicking a card opens this panel next to it. The popup owns its
 * surface, reveal animation and scrolling; the card keeps owning the setting
 * controls, so every slider, list and colour picker stays exactly as usable as
 * it was inline. The panel flips to the other side of the card when the window
 * edge is closer, and clamps to the window on both axes.</p>
 */
final class ModuleSettingsPopup {
	private static final int WIDTH = 320;
	private static final int HEADER_H = 40;
	private static final int FOOTER_H = 10;
	private static final int MARGIN = 10;
	private static final int PAD = 10;
	private static final int CLOSE_SIZE = 18;
	private static final int RAIL_W = 4;

	private final Anim open = new Anim(0f, 22f);
	private final UiScrollbar scrollbar = new UiScrollbar();

	private ModuleCard card;
	private boolean visible;

	private int x;
	private int y;
	private int w;
	private int h;
	private int bodyTop;
	private int bodyH;
	private int closeX;
	private int closeY;

	/** True while a popup is showing. */
	boolean isOpen() {
		return visible && card != null;
	}

	ModuleCard card() {
		return card;
	}

	/** Opens for a card, or closes when that card's popup is already open. */
	void open(ModuleCard target) {
		if (target == null) {
			return;
		}
		if (visible && card == target) {
			close();
			return;
		}
		if (card != null && card != target) {
			card.clearTextInput();
			card.setSettingsOpen(false);
		}
		card = target;
		card.setSettingsOpen(true);
		visible = true;
		open.snap(0f);
		scrollbar.reset();
	}

	/** Closes the popup and releases the card's keyboard focus. */
	void close() {
		if (card != null) {
			card.clearTextInput();
			card.setSettingsOpen(false);
		}
		card = null;
		visible = false;
		scrollbar.reset();
	}

	boolean contains(double mx, double my) {
		return isOpen() && mx >= x && mx < x + w && my >= y && my < y + h;
	}

	void render(UiCanvas c, UiType type, int mouseX, int mouseY, float delta,
			int winX, int winY, int winW, int winH) {
		if (!isOpen()) {
			return;
		}
		open.to(1f, delta);
		float t = Motion.easeOutCubic(Motion.clamp01(open.value()));

		w = Math.min(WIDTH, winW - MARGIN * 2);
		int contentH = Math.max(0, card.settingsHeight());
		int desired = HEADER_H + contentH + FOOTER_H;
		h = Math.max(HEADER_H + FOOTER_H + 24, Math.min(winH - MARGIN * 2, desired));
		// Outside the card when there is room, otherwise on its other side.
		x = card.x() + card.width() + UiTheme.SP_3;
		if (x + w > winX + winW - MARGIN) {
			x = card.x() - w - UiTheme.SP_3;
		}
		x = Math.max(winX + MARGIN, Math.min(x, winX + winW - MARGIN - w));
		y = Math.max(winY + MARGIN, Math.min(card.y() - 2, winY + winH - MARGIN - h));
		bodyTop = y + HEADER_H;
		bodyH = Math.max(1, h - HEADER_H - FOOTER_H);
		closeX = x + w - PAD - CLOSE_SIZE;
		closeY = y + (HEADER_H - CLOSE_SIZE) / 2;

		scrollbar.update(bodyTop, bodyTop + bodyH, contentH, delta);
		scrollbar.setRailX(x + w - RAIL_W - 4);

		// The reveal unfolds the panel from its header down. Everything is
		// drawn inside that clip, so the animation cannot spill over the GUI.
		int shownH = Math.max(1, Math.round(h * t));
		c.pushClip(x - 2, y - 2, w + 4, shownH + 4);
		Ui.shadow(c, x, y, w, h, UiTheme.RADIUS_LG);
		Ui.surface(c, x, y, w, h, UiTheme.RADIUS_LG, UiTheme.PANEL_TOP, UiTheme.PANEL_BOTTOM);
		Ui.outline(c, x, y, w, h, UiTheme.RADIUS_LG, UiTheme.BORDER);
		Ui.sheen(c, x, y, w, h, UiTheme.RADIUS_LG, (System.currentTimeMillis() % 11000L) / 11000f);
		Ui.frost(c, x + 2, y + 2, w - 4, HEADER_H, 0x08FFFFFF);

		UiText medium = type.medium();
		Ui.drawEllipsized(c, medium, card.title(), x + PAD,
				Ui.centerTextY(y, HEADER_H, medium), w - PAD * 2 - CLOSE_SIZE - UiTheme.SP_4,
				UiTheme.TEXT);

		boolean closeHovered = mouseX >= closeX && mouseX < closeX + CLOSE_SIZE
				&& mouseY >= closeY && mouseY < closeY + CLOSE_SIZE;
		Ui.surface(c, closeX, closeY, CLOSE_SIZE, CLOSE_SIZE, UiTheme.RADIUS,
				closeHovered ? UiTheme.PANEL_ALT : UiTheme.TRACK_TOP,
				closeHovered ? UiTheme.PANEL_TOP : UiTheme.TRACK_BOTTOM);
		Ui.outline(c, closeX, closeY, CLOSE_SIZE, CLOSE_SIZE, UiTheme.RADIUS,
				closeHovered ? UiTheme.ACCENT : UiTheme.BORDER);
		UiIcons.draw(c, UiIcons.Icon.CLOSE, closeX + 5, closeY + 5, 8,
				closeHovered ? UiTheme.TEXT : UiTheme.TEXT_2);

		Ui.fadeLine(c, x + UiTheme.SP_4, bodyTop - 1, w - UiTheme.SP_4 * 2,
				Ui.withAlpha(UiTheme.BORDER, 0xA0));

		c.pushClip(x + 1, bodyTop, w - 1, bodyH);
		int contentX = x + PAD;
		int contentW = Math.max(40, w - PAD * 2 - RAIL_W - 6);
		card.renderSettingsContent(c, type, contentX, bodyTop, contentW,
				(int) Math.round(scrollbar.scroll()), mouseX, mouseY, delta);
		c.popClip();

		scrollbar.render(c);
		c.popClip();

		if (t < 1f) {
			Ui.glow(c, x, y, w, h, UiTheme.ACCENT, (1f - t) * 0.16f);
		}
	}

	/**
	 * Handles a press. Returns false when the press is outside the popup, so
	 * the owner can close it and continue routing the click.
	 */
	boolean mouseClicked(double mx, double my) {
		if (!isOpen() || mx < x || mx >= x + w || my < y || my >= y + h) {
			return false;
		}
		if (mx >= closeX && mx < closeX + CLOSE_SIZE && my >= closeY && my < closeY + CLOSE_SIZE) {
			GuiSounds.close();
			close();
			return true;
		}
		int railX = x + w - RAIL_W - 4;
		if (mx >= railX - 3 && mx < railX + RAIL_W + 3 && my >= bodyTop && my < bodyTop + bodyH) {
			scrollbar.pressed(my);
			return true;
		}
		// Header presses and empty body padding are consumed, never passed to
		// the card grid underneath the popup.
		if (my >= bodyTop && my < bodyTop + bodyH) {
			card.settingsMouseClicked(mx, my);
		}
		return true;
	}

	boolean mouseScrolled(double amount) {
		return isOpen() && scrollbar.onScroll(amount);
	}

	boolean mouseDragged(double mx, double my) {
		if (!isOpen()) {
			return false;
		}
		if (scrollbar.isDragging()) {
			scrollbar.dragged(my);
		} else {
			card.mouseDragged(mx, my);
		}
		return true;
	}

	void mouseReleased(double mx, double my) {
		if (!isOpen()) {
			return;
		}
		scrollbar.released();
		card.mouseReleased(mx, my);
	}
}
