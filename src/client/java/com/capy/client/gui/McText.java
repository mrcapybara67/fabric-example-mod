package com.capy.client.gui;

import net.minecraft.client.gui.Font;

/**
 * {@link UiText} backed by one of Minecraft's {@link Font} instances.
 */
public final class McText implements UiText {
	private final Font font;

	public McText(Font font) {
		this.font = font;
	}

	@Override
	public int width(String text) {
		return text == null || font == null ? 0 : font.width(text);
	}

	@Override
	public int lineHeight() {
		return font == null ? 9 : font.lineHeight;
	}

	@Override
	public void draw(UiCanvas canvas, String text, int x, int y, int argb) {
		if (text == null || text.isEmpty() || font == null || !(canvas instanceof McCanvas mc)) {
			return;
		}
		mc.graphics().drawString(font, text, x, y, argb, false);
	}
}
