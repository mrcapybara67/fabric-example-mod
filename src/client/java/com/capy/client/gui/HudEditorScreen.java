package com.capy.client.gui;

import com.capy.client.config.ClientConfig;
import com.capy.client.keybind.ClientKeybinds;
import com.capy.client.module.modules.hud.HudElementModule;
import com.capy.client.module.modules.hud.HudRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The HUD editor: drag a chip, and it stays there.
 *
 * <p>The editor is deliberately thin. The arrangement itself is not reimplemented
 * here - {@link HudRenderer#prepare} lays the chips out exactly as the game will,
 * and every handle this screen draws is read back from that same layout. A second
 * copy of the arrangement maths would drift from the real one the first time
 * either changed, and a handle two pixels off the chip it moves is worse than no
 * editor at all.</p>
 *
 * <h2>How a drag becomes a setting</h2>
 * Each chip has a base position - the cell it is anchored to, plus its place in
 * that cell's stack - and a nudge stored in its own settings. A drag records
 * where inside the chip the pointer went down, and every mouse move writes
 * {@code pointer - grab - base} as the new nudge. That is why the chip follows
 * the pointer exactly rather than jumping to it, and why letting go and grabbing
 * again never moves it.
 *
 * <p>The nudge is clamped by the module itself, so a drag that runs off the
 * window stores the furthest legal position instead of a value the setting
 * refuses - which would snap the chip back while the pointer kept moving.</p>
 *
 * <h2>Leaving</h2>
 * Escape closes the screen through vanilla's own path, which saves the config and
 * hands the mouse and the held keys back. Dragging also saves on release, so a
 * crash after a drag cannot lose the layout.
 */
public final class HudEditorScreen extends Screen {
	private static final int HINT_TOP = 10;
	/** The chip being dragged, or null. */
	private HudElementModule dragging;
	/** Where inside the chip the drag started, in pixels. */
	private int grabX;
	private int grabY;

	public HudEditorScreen() {
		super(Component.translatable("capy.hud.editor"));
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		// A scrim rather than a blurred world: the point of the editor is to see
		// the chips against the scene they will sit on, so the world stays
		// visible and only loses contrast.
		graphics.fill(0, 0, this.width, this.height, 0x66101018);

		HudRenderer.prepare(this.width, this.height);
		int count = HudRenderer.slotCount();
		int hovered = -1;
		for (int i = 0; i < count; i++) {
			HudElementModule module = HudRenderer.slotModule(i);
			int x = HudRenderer.slotX(i);
			int y = HudRenderer.slotY(i);
			int w = HudRenderer.slotWidth(i);
			int h = HudRenderer.chipHeight();
			boolean hot = module == dragging
					|| (mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h);
			if (hot) {
				hovered = i;
				graphics.fill(x, y, x + w, y + h, 0x24FFFFFF);
			}
			drawBrackets(graphics, x, y, w, h, hot ? UiTheme.ACCENT : Ui.withAlpha(UiTheme.ACCENT, 0xA0));
		}

		drawHeader(graphics, mouseX, mouseY, hovered);
	}

	/** The title, the instructions and the readout of the hovered chip. */
	private void drawHeader(GuiGraphics graphics, int mouseX, int mouseY, int hovered) {
		Font font = this.font != null ? this.font : Minecraft.getInstance().font;
		if (font == null) {
			return;
		}
		String title = "HUD EDITOR";
		String hint = "drag a chip to move it  \u00b7  right click a chip resets it  \u00b7  esc saves";
		int centerX = this.width / 2;
		int titleW = font.width(title);
		graphics.fill(centerX - titleW / 2 - 10, HINT_TOP - 5, centerX + titleW / 2 + 10,
				HINT_TOP + font.lineHeight + 4, 0xB4101018);
		graphics.drawString(font, title, centerX - titleW / 2, HINT_TOP, UiTheme.ACCENT, true);
		int hintW = font.width(hint);
		graphics.drawString(font, hint, centerX - hintW / 2, HINT_TOP + font.lineHeight + 8,
				UiTheme.TEXT_2, true);

		if (hovered >= 0 && hovered < HudRenderer.slotCount()) {
			HudElementModule module = HudRenderer.slotModule(hovered);
			String detail = module.label().isEmpty() ? module.id() : module.label();
			String offsets = "x " + module.offsetX() + "   y " + module.offsetY();
			graphics.drawString(font, detail, 8, this.height - 26, UiTheme.TEXT, true);
			graphics.drawString(font, offsets, 8, this.height - 15, UiTheme.TEXT_3, true);
		}
	}

	/** Four corner brackets, the same language the chip's own frame uses. */
	private static void drawBrackets(GuiGraphics graphics, int x, int y, int w, int h, int color) {
		int arm = Math.min(8, Math.min(w, h) / 2);
		if (arm <= 0) {
			return;
		}
		// Top left / top right.
		graphics.fill(x, y, x + arm, y + 1, color);
		graphics.fill(x, y, x + 1, y + arm, color);
		graphics.fill(x + w - arm, y, x + w, y + 1, color);
		graphics.fill(x + w - 1, y, x + w, y + arm, color);
		// Bottom left / bottom right.
		graphics.fill(x, y + h - 1, x + arm, y + h, color);
		graphics.fill(x, y + h - arm, x + 1, y + h, color);
		graphics.fill(x + w - arm, y + h - 1, x + w, y + h, color);
		graphics.fill(x + w - 1, y + h - arm, x + w, y + h, color);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		// Re-layout before hit testing: the values behind the chips change every
		// frame (a ping in milliseconds is three characters one moment and two
		// the next), so the width the pointer was drawn over is the width this
		// frame laid out, not the one from the last one.
		HudRenderer.prepare(this.width, this.height);
		int mx = (int) Math.round(event.x());
		int my = (int) Math.round(event.y());
		for (int i = HudRenderer.slotCount() - 1; i >= 0; i--) {
			int x = HudRenderer.slotX(i);
			int y = HudRenderer.slotY(i);
			int w = HudRenderer.slotWidth(i);
			int h = HudRenderer.chipHeight();
			if (mx < x || mx >= x + w || my < y || my >= y + h) {
				continue;
			}
			HudElementModule module = HudRenderer.slotModule(i);
			if (event.button() == 1) {
				module.resetPosition();
				ClientConfig.saveNow();
				return true;
			}
			if (event.button() == 0) {
				dragging = module;
				grabX = mx - x;
				grabY = my - y;
				return true;
			}
		}
		return super.mouseClicked(event, doubled);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (dragging == null) {
			return super.mouseDragged(event, dragX, dragY);
		}
		HudRenderer.prepare(this.width, this.height);
		int index = indexOf(dragging);
		if (index < 0) {
			// The chip vanished mid drag (its module was switched off by a
			// keybind). Dropping the drag is the only honest answer.
			dragging = null;
			return true;
		}
		int targetX = (int) Math.round(event.x()) - grabX - HudRenderer.slotBaseX(index);
		int targetY = (int) Math.round(event.y()) - grabY - HudRenderer.slotBaseY(index);
		dragging.moveTo(targetX, targetY);
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragging != null) {
			dragging = null;
			// Saved on release rather than only on close, so a crash after a drag
			// cannot lose the layout the player just built.
			ClientConfig.saveNow();
			return true;
		}
		return super.mouseReleased(event);
	}

	private static int indexOf(HudElementModule module) {
		for (int i = 0; i < HudRenderer.slotCount(); i++) {
			if (HudRenderer.slotModule(i) == module) {
				return i;
			}
		}
		return -1;
	}

	@Override
	public void onClose() {
		ClientConfig.saveNow();
		super.onClose();
		// Vanilla re-grabs the mouse, but not when the window was unfocused at
		// that instant; the tick retries until it worked.
		ClientKeybinds.requestInputRestore();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
