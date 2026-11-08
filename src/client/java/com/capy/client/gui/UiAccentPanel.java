package com.capy.client.gui;

/**
 * The client's colour studio: a full HSV picker for the window accent plus a
 * row of quick palettes.
 *
 * It is a floating overlay rather than a settings row. The picker is the
 * existing {@link UiColorPicker}, bound to the standalone setting in
 * {@link GuiColors}; no module had to grow a fake "GUI colour" setting just to
 * host it.
 */
public final class UiAccentPanel {
	private static final int PAD = 16;
	private static final int HEADER_H = 50;
	private static final int SWATCH = 20;
	private static final int SWATCH_GAP = 10;
	private static final int[] PRESETS = {
			0xFFFF8FB8, 0xFFFF6FAE, 0xFFC8A2FF, 0xFF8FB8FF,
			0xFF6FDFFF, 0xFF7CF0C0, 0xFFFFD166, 0xFFFF6B6B
	};

	private final UiColorPicker windowPicker = new UiColorPicker(GuiColors.window(), false);
	private final Anim reveal = new Anim(0f, 18f);

	private boolean open;
	private int width;
	private int height;
	private int panelX;
	private int panelY;
	private int panelW;
	private int panelH;
	private int windowRowY;
	private int presetY;
	private int swatchX;
	private int closeX;
	private int closeY;

	public boolean isOpen() {
		return open;
	}

	public void toggle() {
		open = !open;
		if (open) {
			// The studio is opened to pick a colour, not to look at a closed
			// row: the picker starts expanded so H/S/V are one click away.
			windowPicker.setOpen(true);
		}
	}

	public void close() {
		open = false;
		// A close during a drag must end the drag, or re-opening the studio and
		// moving the pointer would keep editing a track the user never grabbed.
		windowPicker.released();
	}

	/** Closes the panel if it is open; used by Escape before closing the GUI. */
	public boolean closeIfOpen() {
		if (!open) {
			return false;
		}
		close();
		return true;
	}


	// ---------------------------------------------------------------- layout

	private void layout() {
		int pickerW = Math.min(420, width - 48);
		panelW = Math.max(250, pickerW + PAD * 2);
		panelX = (width - panelW) / 2;

		int content = HEADER_H + windowPicker.height()
				+ 14 + 12 + SWATCH + 8 + 12 + PAD;
		// The picker is the point of the sheet, so it is never the part that
		// gets clipped; the height simply follows it.
		panelH = Math.min(height - 16, content);
		panelY = (height - panelH) / 2;
		placeRows();
		swatchX = panelX + PAD;
		closeX = panelX + panelW - PAD - 18;
		closeY = panelY + 14;
	}

	/** Binds the picker once the final panel origin is known. */
	private void placeRows() {
		windowRowY = panelY + HEADER_H;
		windowPicker.bind(panelX + PAD, windowRowY, panelW - PAD * 2, "Window accent");
		presetY = windowRowY + windowPicker.height() + 26;
	}

	/** True while the palette row is inside the sheet rather than past it. */
	private boolean presetsFit() {
		return presetY + SWATCH + 4 <= panelY + panelH - 6;
	}

	private int presetAt(double mx, double my) {
		// A swatch that was drawn outside the sheet would still answer a
		// click somewhere off the panel, so the row is inert until it fits.
		if (!presetsFit()) {
			return -1;
		}
		for (int i = 0; i < PRESETS.length; i++) {
			int x = swatchX + i * (SWATCH + SWATCH_GAP);
			if (mx >= x - 2 && mx < x + SWATCH + 2 && my >= presetY - 2 && my < presetY + SWATCH + 2) {
				return i;
			}
		}
		return -1;
	}

	// ---------------------------------------------------------------- render

	public void render(UiCanvas c, UiType type, int screenWidth, int screenHeight,
			int mouseX, int mouseY, float delta) {
		this.width = screenWidth;
		this.height = screenHeight;
		GuiColors.sync();
		reveal.to(open ? 1f : 0f, delta);
		float t = Motion.clamp01(reveal.value());
		if (t <= 0.02f) {
			return;
		}
		layout();

		int alpha = Math.round(255 * t);
		// The scrim makes the studio the focus and, more importantly, tells the
		// user that clicks outside it are about to dismiss it. It is heavy on
		// purpose: a light veil left the window title legible through the panel
		// and made the two compete for the same words.
		c.fill(0, 0, width, height, Ui.withAlpha(0xB4000000, Math.round(0xB4 * t)));

		int y = panelY;
		Ui.shadow(c, panelX, y, panelW, panelH, UiTheme.RADIUS_LG);
		Ui.surface(c, panelX, y, panelW, panelH, UiTheme.RADIUS_LG,
				Ui.withAlpha(UiTheme.PANEL_TOP, alpha), Ui.withAlpha(UiTheme.PANEL_BOTTOM, alpha));
		Ui.outline(c, panelX, y, panelW, panelH, UiTheme.RADIUS_LG, Ui.withAlpha(UiTheme.BORDER, alpha));
		Ui.innerBevel(c, panelX, y, panelW, panelH, UiTheme.RADIUS_LG, 0x16FFFFFF, 0x22000000);
		// A slim accent rule signals what the panel controls before the title
		// is read.
		Ui.surface(c, panelX + UiTheme.RADIUS_LG, y + 1, panelW - UiTheme.RADIUS_LG * 2, 1, 0,
				Ui.withAlpha(UiTheme.ACCENT, Math.round(0xB0 * t)));

		UiIcons.draw(c, UiIcons.Icon.PALETTE, panelX + PAD, y + 17, 16,
				Ui.withAlpha(UiTheme.ACCENT, alpha));
		UiText bold = type.bold();
		UiText regular = type.regular();
		bold.draw(c, "GUI COLOURS", panelX + PAD + 24, y + 13, Ui.withAlpha(UiTheme.TEXT, alpha));
		regular.draw(c, "Window accent", panelX + PAD + 24, y + 27,
				Ui.withAlpha(UiTheme.TEXT_3, alpha));

		// Close.
		boolean closeHovered = mouseX >= closeX && mouseX < closeX + 18
				&& mouseY >= closeY && mouseY < closeY + 18;
		Ui.surface(c, closeX, closeY, 18, 18, 6,
				Ui.withAlpha(closeHovered ? UiTheme.ACCENT : UiTheme.PANEL_ALT, alpha),
				Ui.withAlpha(closeHovered ? UiTheme.ACCENT_DEEP : UiTheme.PANEL_TOP, alpha));
		UiIcons.draw(c, UiIcons.Icon.CLOSE, closeX + 5, closeY + 5, 8,
				Ui.withAlpha(closeHovered ? UiTheme.ON_ACCENT : UiTheme.TEXT_2, alpha));

		// Price of admission: the pickers draw with live theme tokens, which is
		// exactly what the user is changing, so they preview the result.
		windowPicker.render(c, regular, mouseX, mouseY, delta);

		// On a sheet too short to hold them the palettes are dropped along
		// with their caption: half a row of swatches below the bottom edge
		// would still have been clickable from off the panel.
		if (presetsFit()) {
			regular.draw(c, "QUICK PALETTES", swatchX, presetY - 15, Ui.withAlpha(UiTheme.TEXT_3, alpha));
			for (int i = 0; i < PRESETS.length; i++) {
				int x = swatchX + i * (SWATCH + SWATCH_GAP);
				boolean hovered = mouseX >= x - 2 && mouseX < x + SWATCH + 2
						&& mouseY >= presetY - 2 && mouseY < presetY + SWATCH + 2;
				int color = PRESETS[i];
				Ui.circle(c, x + SWATCH / 2, presetY + SWATCH / 2, SWATCH / 2,
						Ui.withAlpha(color, alpha));
				Ui.ring(c, x + SWATCH / 2, presetY + SWATCH / 2, SWATCH / 2 + (hovered ? 2 : 1), 1,
						Ui.withAlpha(hovered ? 0xFFFFFFFF : 0x66FFFFFF, alpha));
				if (isSelected(color)) {
					UiIcons.draw(c, UiIcons.Icon.CHECK, x + 5, presetY + 5, 10,
							Ui.withAlpha(contrast(color), alpha));
				}
			}
		}

		// Only when there is room: on a 360 pixel tall GUI the pickers matter,
		// and this sentence would be the first thing to be cut off.
		if (presetY + SWATCH + 8 + regular.lineHeight() <= panelY + panelH - 6) {
			regular.draw(c, "Changes apply instantly and are saved with the client.",
					swatchX, presetY + SWATCH + 8, Ui.withAlpha(UiTheme.TEXT_3, alpha));
		}
	}

	private boolean isSelected(int color) {
		return (GuiColors.windowArgb() & 0x00FFFFFF) == (color & 0x00FFFFFF);
	}

	private static int contrast(int color) {
		return Ui.luminance(color) > 0.62f ? 0xFF3A0F22 : 0xFFFFFFFF;
	}

	// ----------------------------------------------------------------- input

	/** Returns true when the panel consumed the click (including a dismiss). */
	public boolean mouseClicked(double mx, double my) {
		if (!open) {
			return false;
		}
		layout();
		if (mx < panelX || mx >= panelX + panelW || my < panelY || my >= panelY + panelH) {
			close();
			return true;
		}
		if (mx >= closeX && mx < closeX + 18 && my >= closeY && my < closeY + 18) {
			close();
			return true;
		}
		if (windowPicker.mouseClicked(mx, my)) {
			GuiColors.sync();
			return true;
		}
		int preset = presetAt(mx, my);
		if (preset >= 0) {
			GuiColors.window().set(PRESETS[preset]);
			GuiColors.sync();
			return true;
		}
		return true;
	}

	public boolean mouseDragged(double mx, double my) {
		if (!open) {
			return false;
		}
		boolean dragged = windowPicker.mouseDragged(mx);
		if (dragged) {
			GuiColors.sync();
		}
		return dragged;
	}

	/** Returns true when a colour track was being dragged. */
	public boolean mouseReleased() {
		if (!open) {
			return false;
		}
		boolean was = windowPicker.released();
		if (was) {
			GuiColors.sync();
		}
		return was;
	}
}
