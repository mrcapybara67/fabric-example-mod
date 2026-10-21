package com.capy.client.gui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * {@link UiCanvas} backed by Minecraft's {@link GuiGraphics}.
 *
 * Scissor regions are pushed and popped in pairs; Minecraft keeps its own
 * intersecting scissor stack, so nested clips behave correctly.
 */
public final class McCanvas implements UiCanvas {
	private final GuiGraphics graphics;
	private int clipDepth;

	private McCanvas(GuiGraphics graphics) {
		this.graphics = graphics;
	}

	public static McCanvas of(GuiGraphics graphics) {
		return new McCanvas(graphics);
	}

	/** The underlying Minecraft graphics handle. */
	public GuiGraphics graphics() {
		return graphics;
	}

	@Override
	public void fill(int x1, int y1, int x2, int y2, int argb) {
		if (x2 <= x1 || y2 <= y1) {
			return;
		}
		graphics.fill(x1, y1, x2, y2, argb);
	}

	@Override
	public void pushClip(int x, int y, int width, int height) {
		if (width <= 0 || height <= 0) {
			// Still push an empty region so the matching pop stays balanced.
			graphics.enableScissor(x, y, x, y);
		} else {
			graphics.enableScissor(x, y, x + width, y + height);
		}
		clipDepth++;
	}

	@Override
	public void popClip() {
		if (clipDepth > 0) {
			graphics.disableScissor();
			clipDepth--;
		}
	}
}
