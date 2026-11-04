package com.capy.client.gui;

import com.capy.client.config.ClientConfig;
import com.capy.client.keybind.ClientKeybinds;
import com.capy.client.module.modules.hud.HudElementModule;
import com.capy.client.module.modules.hud.HudPanel;
import com.capy.client.module.modules.hud.HudRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The HUD editor: drag a chip or a box, and it stays there.
 *
 * <p>The editor is deliberately thin. The arrangement itself is not reimplemented
 * here - {@link HudRenderer#prepare} lays everything out exactly as the game
 * will, and every handle this screen draws is read back from that same layout. A
 * second copy of the arrangement maths would drift from the real one the first
 * time either changed, and a handle two pixels off the chip it moves is worse
 * than no editor at all.</p>
 *
 * <h2>Two kinds of thing, one drag</h2>
 * A HUD element is a measured chip; a panel such as the staff list sizes
 * itself. Both place themselves the same way - a cell anchor plus a nudge - so
 * the drag is written once against {@link HudElementModule} and
 * {@link HudPanel} rather than once per kind. Which one is under the pointer is
 * decided from the layout, with panels tested first because they are drawn
 * last.</p>
 *
 * <h2>How a drag becomes a setting</h2>
 * Each thing has a base position - the cell it is anchored to, plus its place in
 * that cell's stack - and a nudge stored in its own settings. A drag records
 * where inside it the pointer went down, and every mouse move writes
 * {@code pointer - grab - base} as the new nudge. That is why it follows the
 * pointer exactly rather than jumping to it, and why letting go and grabbing
 * again never moves it.
 *
 * <p>The nudge is clamped by the owner itself, so a drag that runs off the
 * window stores the furthest legal position instead of a value the setting
 * refuses - which would snap back while the pointer kept moving.</p>
 *
 * <h2>Leaving</h2>
 * Escape closes the screen through vanilla's own path, which saves the config and
 * hands the mouse and the held keys back. Dragging also saves on release, so a
 * crash after a drag cannot lose the layout.
 */
public final class HudEditorScreen extends Screen {
	private static final int HINT_TOP = 10;
	/** The chip or panel being dragged, or null. */
	private Object dragging;
	/** Where inside the thing the drag started, in pixels. */
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
		// The chips and panels themselves, over the scrim: what is being placed
		// has to be seen exactly as the game will draw it.
		HudRenderer.drawPrepared(graphics);

		int hoveredChip = -1;
		int hoveredPanel = -1;
		// Panels first, because they are drawn last: the thing on top is the
		// thing the pointer is over.
		for (int i = HudRenderer.panelCount() - 1; i >= 0; i--) {
			int x = HudRenderer.panelX(i);
			int y = HudRenderer.panelY(i);
			int w = HudRenderer.panelWidth(i);
			int h = HudRenderer.panelHeight(i);
			boolean hot = HudRenderer.panelAt(i) == dragging
					|| (mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h);
			if (hot) {
				hoveredPanel = i;
				graphics.fill(x, y, x + w, y + h, 0x24FFFFFF);
			}
			drawBrackets(graphics, x, y, w, h, hot ? UiTheme.ACCENT : Ui.withAlpha(UiTheme.ACCENT, 0xA0));
		}

		for (int i = 0; i < HudRenderer.slotCount(); i++) {
			HudElementModule module = HudRenderer.slotModule(i);
			int x = HudRenderer.slotX(i);
			int y = HudRenderer.slotY(i);
			int w = HudRenderer.slotWidth(i);
			int h = HudRenderer.chipHeight();
			boolean hot = module == dragging
					|| (mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h);
			if (hot && hoveredPanel < 0) {
				hoveredChip = i;
				graphics.fill(x, y, x + w, y + h, 0x24FFFFFF);
			}
			drawBrackets(graphics, x, y, w, h, hot ? UiTheme.ACCENT : Ui.withAlpha(UiTheme.ACCENT, 0xA0));
		}

		drawHeader(graphics, mouseX, mouseY, hoveredChip, hoveredPanel);
	}

	/** The title, the instructions and the readout of what is hovered. */
	private void drawHeader(GuiGraphics graphics, int mouseX, int mouseY, int hoveredChip, int hoveredPanel) {
		Font font = this.font != null ? this.font : Minecraft.getInstance().font;
		if (font == null) {
			return;
		}
		String title = "HUD EDITOR";
		String hint = "drag a chip or box to move it  \u00b7  right click resets it  \u00b7  esc saves";
		int centerX = this.width / 2;
		int titleW = font.width(title);
		graphics.fill(centerX - titleW / 2 - 10, HINT_TOP - 5, centerX + titleW / 2 + 10,
				HINT_TOP + font.lineHeight + 4, 0xB4101018);
		graphics.drawString(font, title, centerX - titleW / 2, HINT_TOP, UiTheme.ACCENT, true);
		int hintW = font.width(hint);
		graphics.drawString(font, hint, centerX - hintW / 2, HINT_TOP + font.lineHeight + 8,
				UiTheme.TEXT_2, true);

		String detail = null;
		String offsets = null;
		if (hoveredPanel >= 0) {
			HudPanel panel = HudRenderer.panelAt(hoveredPanel);
			detail = panel.panelLabel();
			offsets = "x " + panel.offsetX() + "   y " + panel.offsetY();
		} else if (hoveredChip >= 0) {
			HudElementModule module = HudRenderer.slotModule(hoveredChip);
			detail = module.label().isEmpty() ? module.id() : module.label();
			offsets = "x " + module.offsetX() + "   y " + module.offsetY();
		}
		if (detail != null) {
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

		for (int i = HudRenderer.panelCount() - 1; i >= 0; i--) {
			if (!inside(mx, my, HudRenderer.panelX(i), HudRenderer.panelY(i),
					HudRenderer.panelWidth(i), HudRenderer.panelHeight(i))) {
				continue;
			}
			HudPanel panel = HudRenderer.panelAt(i);
			if (event.button() == 1) {
				reset(panel);
				ClientConfig.saveNow();
				return true;
			}
			if (event.button() == 0) {
				startDrag(panel, mx, my, HudRenderer.panelX(i), HudRenderer.panelY(i));
				return true;
			}
		}

		for (int i = HudRenderer.slotCount() - 1; i >= 0; i--) {
			if (!inside(mx, my, HudRenderer.slotX(i), HudRenderer.slotY(i),
					HudRenderer.slotWidth(i), HudRenderer.chipHeight())) {
				continue;
			}
			HudElementModule module = HudRenderer.slotModule(i);
			if (event.button() == 1) {
				module.resetPosition();
				ClientConfig.saveNow();
				return true;
			}
			if (event.button() == 0) {
				startDrag(module, mx, my, HudRenderer.slotX(i), HudRenderer.slotY(i));
				return true;
			}
		}
		return super.mouseClicked(event, doubled);
	}

	private void startDrag(Object target, int mx, int my, int x, int y) {
		dragging = target;
		grabX = mx - x;
		grabY = my - y;
	}

	private static boolean inside(int mx, int my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (dragging == null) {
			return super.mouseDragged(event, dragX, dragY);
		}
		HudRenderer.prepare(this.width, this.height);
		if (baseOf(dragging) == null) {
			// The thing vanished mid drag (its module was switched off by a
			// keybind). Dropping the drag is the only honest answer.
			dragging = null;
			return true;
		}
		// Where the pointer wants its top left corner, in pixels.
		int wantX = (int) Math.round(event.x()) - grabX;
		int wantY = (int) Math.round(event.y()) - grabY;
		// Re-anchor to the nearest of the nine cells before writing the nudge.
		// A nudge is capped at a few hundred pixels - right for a setting, wrong
		// for a drag that crosses the window - so a drag that only wrote an
		// offset would stop dead halfway while the pointer kept going.
		int best = anchorOf(dragging);
		long bestCost = Long.MAX_VALUE;
		for (int anchor = 0; anchor < 9; anchor++) {
			setAnchor(dragging, anchor);
			HudRenderer.prepare(this.width, this.height);
			int[] base = baseOf(dragging);
			if (base == null) {
				dragging = null;
				return true;
			}
			long dx = base[0] - wantX;
			long dy = base[1] - wantY;
			long cost = dx * dx + dy * dy;
			if (cost < bestCost) {
				bestCost = cost;
				best = anchor;
			}
		}
		setAnchor(dragging, best);
		HudRenderer.prepare(this.width, this.height);
		int[] base = baseOf(dragging);
		if (base == null) {
			dragging = null;
			return true;
		}
		// The residual after the best cell is at most half a cell, so this is
		// inside the nudge range and it lands exactly under the pointer.
		moveTo(dragging, wantX - base[0], wantY - base[1]);
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

	// ------------------------------------------------------------- placement

	/** The base position and size of a dragged thing, or null when not laid out. */
	private static int[] baseOf(Object target) {
		if (target instanceof HudElementModule hud) {
			for (int i = 0; i < HudRenderer.slotCount(); i++) {
				if (HudRenderer.slotModule(i) == hud) {
					return new int[]{HudRenderer.slotBaseX(i), HudRenderer.slotBaseY(i),
							HudRenderer.slotWidth(i), HudRenderer.chipHeight()};
				}
			}
			return null;
		}
		if (target instanceof HudPanel panel) {
			for (int i = 0; i < HudRenderer.panelCount(); i++) {
				if (HudRenderer.panelAt(i) == panel) {
					return new int[]{HudRenderer.panelBaseX(i), HudRenderer.panelBaseY(i),
							HudRenderer.panelWidth(i), HudRenderer.panelHeight(i)};
				}
			}
		}
		return null;
	}

	private static int anchorOf(Object target) {
		if (target instanceof HudElementModule hud) {
			return hud.anchor();
		}
		return target instanceof HudPanel panel ? panel.anchor() : 0;
	}

	private static void setAnchor(Object target, int anchor) {
		if (target instanceof HudElementModule hud) {
			hud.setAnchor(anchor);
		} else if (target instanceof HudPanel panel) {
			panel.setAnchor(anchor);
		}
	}

	private static void moveTo(Object target, int x, int y) {
		if (target instanceof HudElementModule hud) {
			hud.moveTo(x, y);
		} else if (target instanceof HudPanel panel) {
			panel.moveTo(x, y);
		}
	}

	private static void reset(Object target) {
		if (target instanceof HudElementModule hud) {
			hud.resetPosition();
		} else if (target instanceof HudPanel panel) {
			panel.resetPosition();
		}
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
