package com.capy.client.gui;

import com.capy.client.module.WatchlistSetting;
import java.util.List;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

/**
 * Watchlist editor: a text field for entering a username, a button that adds
 * it, and a list of watched names with a remove button each.
 *
 * The component owns the validation feedback (invalid name, duplicate, list
 * full) and clears the field only when an entry was actually accepted.
 */
public class UiWatchlist {
	/** Data source the editor works against. */
	public interface Model {
		List<String> entries();

		WatchlistSetting.AddResult add(String name);

		boolean remove(String name);
	}

	private static final int INPUT_H = 22;
	private static final int ENTRY_H = 17;
	private static final int HEADER_H = 15;
	/** Reserved height of the validation message line. */
	private static final int STATUS_H = 12;
	/** Entries rendered before the list is truncated. */
	private static final int MAX_VISIBLE = 8;

	private final UiTextField field = new UiTextField();
	private final Model model;
	private final String label;
	private String status = "";
	private long statusUntil;

	public UiWatchlist(Model model, String label) {
		this.model = model;
		this.label = label;
		this.field.setPlaceholder("Username");
		this.field.onSubmit(this::submit);
	}

	/** True while the text field owns the keyboard. */
	public boolean isFocused() {
		return field.isFocused();
	}

	/** Removes the focus from the text field. */
	public void clearFocus() {
		field.setFocused(false);
	}

	public boolean charTyped(CharacterEvent event) {
		return field.charTyped(event);
	}

	public boolean keyPressed(KeyEvent event) {
		return field.keyPressed(event);
	}

	/** Height of the editor for the current number of entries. */
	public int height() {
		int entries = Math.min(MAX_VISIBLE, model.entries().size());
		// The status line is always reserved so the card never jumps when a
		// message appears or expires.
		return HEADER_H + INPUT_H + UiTheme.SP_2 + STATUS_H + entries * ENTRY_H
				+ (model.entries().size() > MAX_VISIBLE ? 12 : 0);
	}

	/** Attempts to add the typed name. */
	private void submit() {
		String name = field.text().trim();
		WatchlistSetting.AddResult result = model.add(name);
		switch (result) {
			case ADDED -> {
				field.clear();
				status = "";
			}
			case INVALID -> status = "Not a valid username";
			case DUPLICATE -> status = "Already watched";
			case FULL -> status = "Watchlist is full";
		}
		if (!status.isEmpty()) {
			statusUntil = System.currentTimeMillis() + 2200L;
		}
	}

	public void render(UiCanvas c, UiText font, int x, int y, int w, int mouseX, int mouseY, float delta) {
		int cy = y;

		// Header: the setting label.
		font.draw(c, label, x, cy, UiTheme.TEXT_2);
		cy += HEADER_H;

		// Input + add button.
		int addW = 22;
		int fieldW = Math.max(30, w - addW - UiTheme.SP_2);
		field.bind(x, cy, fieldW, INPUT_H);
		field.render(c, font, mouseX, mouseY, delta);

		int addX = x + fieldW + UiTheme.SP_2;
		boolean addHovered = mouseX >= addX && mouseX < addX + addW && mouseY >= cy && mouseY < cy + INPUT_H;
		Ui.surface(c, addX, cy, addW, INPUT_H, UiTheme.RADIUS,
				addHovered ? UiTheme.ACCENT : UiTheme.TRACK_TOP,
				addHovered ? UiTheme.ACCENT_DEEP : UiTheme.TRACK_BOTTOM);
		Ui.surfaceEdge(c, addX, cy, addW, INPUT_H, UiTheme.RADIUS, 0x33FFFFFF, 0x12FFFFFF);
		UiIcons.draw(c, UiIcons.Icon.PLUS, addX + 7, cy + (INPUT_H - 8) / 2, 8,
				addHovered ? UiTheme.ON_ACCENT : UiTheme.TEXT_2);
		cy += INPUT_H + UiTheme.SP_2;

		// Status line (only while the message is fresh), centred in the band
		// that is reserved for it so it can never push the list down.
		if (!status.isEmpty() && System.currentTimeMillis() < statusUntil) {
			font.draw(c, status, x, Ui.centerTextY(cy, STATUS_H, font), UiTheme.ACCENT_DEEP);
		}
		cy += STATUS_H;

		List<String> entries = model.entries();
		if (entries.isEmpty()) {
			font.draw(c, "No usernames watched yet", x, cy, UiTheme.TEXT_3);
			return;
		}

		int shown = Math.min(MAX_VISIBLE, entries.size());
		for (int i = 0; i < shown; i++) {
			String entry = entries.get(i);
			int rowY = cy + i * ENTRY_H;

			// Entry chip.
			Ui.surface(c, x, rowY, w, ENTRY_H - 2, UiTheme.RADIUS,
					UiTheme.PANEL_ALT, UiTheme.PANEL_TOP);
			Ui.outline(c, x, rowY, w, ENTRY_H - 2, UiTheme.RADIUS, 0x2EFFFFFF);

			// Blossom bullet.
			UiIcons.draw(c, UiIcons.Icon.BLOSSOM, x + UiTheme.SP_2, rowY + 3, 9, UiTheme.ACCENT_2);
			font.draw(c, entry, x + UiTheme.SP_2 + 13, Ui.centerTextY(rowY, ENTRY_H - 2, font), UiTheme.TEXT);

			// Remove button.
			int rm = 12;
			int rx = x + w - rm - UiTheme.SP_2;
			int ry = rowY + (ENTRY_H - 2 - rm) / 2;
			boolean rmHovered = mouseX >= rx && mouseX < rx + rm && mouseY >= ry && mouseY < ry + rm;
			Ui.surface(c, rx, ry, rm, rm, rm / 2,
					rmHovered ? Ui.withAlpha(UiTheme.ACCENT_DEEP, 0x66) : 0x00000000,
					rmHovered ? Ui.withAlpha(UiTheme.ACCENT_DEEP, 0x40) : 0x00000000);
			UiIcons.draw(c, UiIcons.Icon.CLOSE, rx + 3, ry + 3, 6,
					rmHovered ? UiTheme.TEXT : UiTheme.TEXT_3);
		}
		cy += shown * ENTRY_H;

		if (entries.size() > MAX_VISIBLE) {
			font.draw(c, "+" + (entries.size() - MAX_VISIBLE) + " more", x, cy, UiTheme.TEXT_3);
		}
	}

	/** Handles a click inside the editor. Returns true when consumed. */
	public boolean mouseClicked(double mx, double my) {
		int w = lastWidth;
		int x = lastX;
		int y = lastY;
		if (w <= 0) {
			return false;
		}

		// The add button.
		int addW = 22;
		int fieldW = Math.max(30, w - addW - UiTheme.SP_2);
		int inputY = y + HEADER_H;
		int addX = x + fieldW + UiTheme.SP_2;
		if (mx >= addX && mx < addX + addW && my >= inputY && my < inputY + INPUT_H) {
			submit();
			return true;
		}

		// Remove buttons.
		int cy = inputY + INPUT_H + UiTheme.SP_2 + STATUS_H;
		List<String> entries = model.entries();
		int shown = Math.min(MAX_VISIBLE, entries.size());
		for (int i = 0; i < shown; i++) {
			int rowY = cy + i * ENTRY_H;
			int rm = 12;
			int rx = x + w - rm - UiTheme.SP_2;
			int ry = rowY + (ENTRY_H - 2 - rm) / 2;
			if (mx >= rx && mx < rx + rm && my >= ry && my < ry + rm) {
				model.remove(entries.get(i));
				return true;
			}
		}

		// The text field itself.
		if (mx >= x && mx < x + fieldW && my >= inputY && my < inputY + INPUT_H) {
			field.setFocused(true);
			return true;
		}
		return false;
	}

	private int lastX;
	private int lastY;
	private int lastWidth;

	/** Records the geometry of the last frame for hit-testinc. */
	public void bind(int x, int y, int width) {
		this.lastX = x;
		this.lastY = y;
		this.lastWidth = width;
	}
}
