package com.griefer.client.gui;

import com.griefer.client.keybind.ClientKeybinds;
import com.griefer.client.module.Category;
import com.griefer.client.module.Module;
import com.griefer.client.module.ModuleManager;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The Griefer ClickGUI.
 *
 * Look: dark terminal panels with chamfered corners, a scanline stripe,
 * an inverted header bar and a bracket-branded footer. One accent color
 * per category. Nothing here resembles Meteor's rounded soft-pastel windows.
 *
 * Interaction summary:
 *   Right Shift (rebindable) - open/close
 *   Left click module header  - toggle module
 *   Right click module header - expand settings
 *   Drag module header        - move panel
 *   Middle click header       - reset settings
 *   Drag slider               - change value
 */
public class ClickGuiScreen extends Screen {
	private static final int MARGIN = 6;
	private static final int BAR_H = 14;

	private final List<ModulePanel> panels = new ArrayList<>();

	// Drag-to-open feedback
	private int openTick = 0;

	private ModulePanel pressed;
	private boolean anySliderActive;

	public ClickGuiScreen() {
		super(Component.translatable("griefer.gui.title"));
	}

	@Override
	protected void init() {
		panels.clear();

		int column = 0;
		for (Category category : Category.values()) {
			List<Module> modules = ModuleManager.get().byCategory(category);
			if (modules.isEmpty()) {
				continue;
			}
			int columnX = MARGIN + column * (ModulePanel.WIDTH + MARGIN);
			int y = BAR_H + MARGIN;

			// Category sign above the stack
			categoryTitles.add(new CategoryTitle(category, columnX, y));
			y += BAR_H;

			for (Module module : modules) {
				panels.add(new ModulePanel(module, columnX, y));
				y += ModulePanel.HEADER + MARGIN;
			}
			column++;
		}
		openTick = 0;
	}

	private final List<CategoryTitle> categoryTitles = new ArrayList<>();

	private static class CategoryTitle {
		final Category category;
		int x;
		int y;

		CategoryTitle(Category category, int x, int y) {
			this.category = category;
			this.x = x;
			this.y = y;
		}
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float tickDelta) {
		g.fill(0, 0, this.width, this.height, 0x770A0E14);

		// Scanline stripes for the terminal vibe
		for (int sy = 0; sy < this.height; sy += 4) {
			g.fill(0, sy, this.width, sy + 2, Theme.SCAN);
		}

		// Top bar
		g.fill(0, 0, this.width, BAR_H, Theme.BG_RAISED);
		g.fill(0, BAR_H - 1, this.width, BAR_H, Theme.ACCENT);
		String header = "GRIEFER // " + Component.translatable("griefer.gui.title").getString();
		g.drawString(this.font, header, MARGIN, (BAR_H - 8) / 2, Theme.TEXT, false);
		String fps = this.minecraft.getFps() + " FPS";
		g.drawString(this.font, fps, this.width - MARGIN - this.font.width(fps), (BAR_H - 8) / 2, Theme.TEXT_DIM, false);

		// Panels (render category titles beneath the bar first)
		for (CategoryTitle title : categoryTitles) {
			String t = title.category.title;
			int tw = this.font.width(t);
			g.fill(title.x, title.y, title.x + tw + 6, title.y + 10, Theme.BG_RAISED);
			g.fill(title.x, title.y, title.x + 2, title.y + 10, title.category.accent);
			g.drawString(this.font, t, title.x + 4, title.y + 1, Theme.TEXT, false);
		}

		for (ModulePanel panel : panels) {
			panel.render(g, this.font, mouseX, mouseY, tickDelta);
		}

		// Footer hints
		String hints = "[LMB] toggle   [RMB] expand   [drag] move   [MMB] reset   [" +
				ClientKeybinds.openClickGui().getTranslatedKeyMessage().getString() + "] close";
		int hy = this.height - 12;
		g.fill(0, hy - 2, this.width, this.height, Theme.BG);
		g.fill(0, hy - 2, 6, hy - 1, Theme.ACCENT);
		g.fill(this.width - 6, hy - 2, this.width, hy - 1, Theme.ACCENT);
		g.drawString(this.font, hints, MARGIN, hy, Theme.TEXT_DIM, false);
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
		double mx = event.x();
		double my = event.y();
		int button = event.button();

		// Top-most panel gets the click; iterate in reverse render order
		for (int i = panels.size() - 1; i >= 0; i--) {
			ModulePanel panel = panels.get(i);
			if (panel.mouseClicked(mx, my, button, this.font)) {
				// bring to front
				if (i != panels.size() - 1) {
					panels.add(panels.remove(i));
				}
				pressed = panel;
				return true;
			}
		}
		return super.mouseClicked(event, doubled);
	}

	@Override
	public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
		if (pressed != null) {
			pressed.mouseDragged(event.x(), event.y(), event.button(), this.font);
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
		if (pressed != null) {
			pressed.mouseReleased(event.x(), event.y(), event.button());
			pressed = null;
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if (ClientKeybinds.openClickGui().matches(event)) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
