package com.capy.client.gui;

/**
 * The client's colour studio: two full HSV pickers, one for the window styles
 * and one for the radial ring, plus a row of quick palettes.
 *
 * It is a floating overlay rather than a settings row so both the window GUI
 * and the radial GUI can open the exact same control. The pickers are the
 * existing {@link UiColorPicker}, bound to the standalone settings in
 * {@link GuiColors}; no module had to grow a fake "GUI colour" setting just to
 * host them.
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
	private final UiColorPicker ringPicker = new UiColorPicker(GuiColors.ring(), false);
	private final Anim reveal = new Anim(0f, 18f);

	private boolean open;
	private int width;
	private int height;
	private int panelX;
	private int panelY;
	private int panelW;
	private int panelH;
	private int windowRowY;
	private int ringRowY;
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
			// The studio is opened to pick a colour, not to look at two closed
			// rows: both pickers start expanded so H/S/V are one click away.
			windowPicker.setOpen(true);
			ringPicker.setOpen(true);
		}
	}

	public void close() {
		open = false;
		// A close during a drag must end the drag, or re-opening the studio and
		// moving the pointer would keep editing a track the user never grabbed.
		windowPicker.released();
		ringPicker.released();
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

		int stacked = HEADER_H + windowPicker.height() + 10 + ringPicker.height()
				+ 14 + 12 + SWATCH + 8 + 12 + PAD;
		// On a 480x270 GUI (the auto scale on a 1080p screen) two stacked
		// pickers are taller than the whole screen. There the studio turns
		// into a two-column sheet instead of clipping the ring picker.
		//
		// The width guard is the floor for those two columns, not a nicety:
		// below about 360 pixels a column stops fitting "Window accent" plus
		// its hex value. Stacking has to be refused anyway once the content is
		// taller than the canvas - a 1280x720 screen at GUI scale 3 is a
		// 427x240 canvas, and stacking there drew the second picker and the
		// whole palette row outside the sheet, off the bottom of the screen.
		boolean twoColumns = stacked > height - 16 && width >= 360;
		int content = twoColumns
				? HEADER_H + Math.max(windowPicker.height(), ringPicker.height())
						+ 10 + 12 + SWATCH + 8 + 12 + PAD
				: stacked;
		// The pickers are the point of the sheet, so they are never the part
		// that gets clipped; the height simply follows them.
		panelH = Math.min(height - 16, content);
		panelY = (height - panelH) / 2;
		placeRows(twoColumns);
		swatchX = panelX + PAD;
		closeX = panelX + panelW - PAD - 18;
		closeY = panelY + 14;
	}

	/** Binds both pickers once the final panel origin is known. */
	private void placeRows(boolean twoColumns) {
		windowRowY = panelY + HEADER_H;
		if (twoColumns) {
			int gap = 14;
			int columnW = Math.max(150, (panelW - PAD * 2 - gap) / 2);
			ringRowY = windowRowY;
			windowPicker.bind(panelX + PAD, windowRowY, columnW, "Window accent");
			ringPicker.bind(panelX + PAD + columnW + gap, ringRowY, columnW, "Ring accent");
		} else {
			windowPicker.bind(panelX + PAD, windowRowY, panelW - PAD * 2, "Window accent");
			ringRowY = windowRowY + windowPicker.height() + 10;
			ringPicker.bind(panelX + PAD, ringRowY, panelW - PAD * 2, "Ring accent");
		}
		presetY = ringRowY + Math.max(windowPicker.height(), ringPicker.height()) + 26;
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
		regular.draw(c, "Window and ring accents", panelX + PAD + 24, y + 27,
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
		ringPicker.render(c, regular, mouseX, mouseY, delta);

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
		int rgb = color & 0x00FFFFFF;
		return (GuiColors.windowArgb() & 0x00FFFFFF) == rgb
				&& (GuiColors.ringArgb() & 0x00FFFFFF) == rgb;
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
		if (ringPicker.mouseClicked(mx, my)) {
			GuiColors.sync();
			return true;
		}
		int preset = presetAt(mx, my);
		if (preset >= 0) {
			int color = PRESETS[preset];
			GuiColors.window().set(color);
			GuiColors.ring().set(color);
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
		dragged |= ringPicker.mouseDragged(mx);
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
		was |= ringPicker.released();
		if (was) {
			GuiColors.sync();
		}
		return was;
	}
}
