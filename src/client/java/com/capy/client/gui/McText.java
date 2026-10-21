package com.capy.client.gui;

import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

/**
 * {@link UiText} backed by one of Minecraft's {@link Font} instances.
 *
 * The font is resolved when it is needed, not when the object is created:
 * the bundled faces are rasterised into a GPU texture and can therefore only
 * be built once the client has a render context. If they are not available
 * yet, the vanilla font is used so text is never lost - the interface keeps
 * its layout and its content, only the typeface differs.
 */
public final class McText implements UiText {
	private final Supplier<Font> supplier;

	public McText(Supplier<Font> supplier) {
		this.supplier = supplier;
	}

	/** The bundled face, or the vanilla font while the bundled one is not ready. */
	private Font resolve() {
		Font font = supplier.get();
		if (font != null) {
			return font;
		}
		Minecraft mc = Minecraft.getInstance();
		return mc == null ? null : mc.font;
	}

	@Override
	public int width(String text) {
		if (text == null) {
			return 0;
		}
		Font font = resolve();
		return font == null ? 0 : font.width(text);
	}

	@Override
	public int lineHeight() {
		Font font = resolve();
		return font == null ? 9 : font.lineHeight;
	}

	@Override
	public void draw(UiCanvas canvas, String text, int x, int y, int argb) {
		if (text == null || text.isEmpty() || !(canvas instanceof McCanvas mc)) {
			return;
		}
		Font font = resolve();
		if (font == null) {
			return;
		}
		mc.graphics().drawString(font, text, x, y, argb, false);
	}
}
