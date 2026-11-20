package com.capy.client.module.modules.antivanish;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The visual half of Anti Vanish.
 *
 * <p>Every pixel here is either {@link GuiGraphics#fill} or the game's own
 * font. There are no TTF files, GUI sprites, texture atlases or shaders in this
 * path, so the alert still appears on a launcher that cannot load custom
 * fonts, textures or post-processing.</p>
 */
public final class AntiVanishHudRenderer {
	private static final int PAD = 7;
	private static final int GAP = 8;
	private static final int ROW_GAP = 3;
	private static final int MAX_WIDTH = 300;
	private static final int RED = 0xFF3B3B;

	private AntiVanishHudRenderer() {
	}

	// ------------------------------------------------------------------ list

	public static int listWidth(Minecraft mc, List<AntiVanishModule.Row> rows) {
		if (mc == null || mc.font == null || rows == null || rows.isEmpty()) {
			return 0;
		}
		Font font = mc.font;
		int widest = 0;
		for (AntiVanishModule.Row row : rows) {
			widest = Math.max(widest, font.width(row.name()) + GAP + font.width(row.reason()));
		}
		return Math.min(MAX_WIDTH, Math.max(120, widest + PAD * 2 + 3));
	}

	public static int listHeight(Minecraft mc, List<AntiVanishModule.Row> rows) {
		if (mc == null || mc.font == null || rows == null || rows.isEmpty()) {
			return 0;
		}
		return PAD * 2 + rows.size() * rowHeight(mc.font);
	}

	public static void renderList(GuiGraphics graphics, int x, int y, int w, int h,
			List<AntiVanishModule.Row> rows) {
		Minecraft mc = Minecraft.getInstance();
		if (graphics == null || mc == null || mc.font == null || rows == null || rows.isEmpty() || w <= 0 || h <= 0) {
			return;
		}
		Font font = mc.font;
		graphics.fill(x, y, x + w, y + h, 0xB0101018);
		graphics.fill(x, y, x + 2, y + h, withAlpha(RED, 210));
		graphics.fill(x + 2, y, x + w, y + 1, withAlpha(RED, 105));
		for (int i = 0; i < rows.size(); i++) {
			AntiVanishModule.Row row = rows.get(i);
			int rowTop = y + PAD + i * rowHeight(font);
			int textY = rowTop + Math.max(0, (rowHeight(font) - font.lineHeight) / 2);
			int nameWidth = Math.min(font.width(row.name()), Math.max(0, w - PAD * 3 - GAP));
			String name = fit(font, row.name(), nameWidth);
			graphics.drawString(font, name, x + PAD, textY, 0xFFFFE0E0, false);
			int reasonX = x + PAD + font.width(name) + GAP;
			int reasonWidth = Math.max(0, x + w - PAD - reasonX);
			graphics.drawString(font, fit(font, row.reason(), reasonWidth), reasonX, textY, 0xFFB8B8C4, false);
		}
	}

	// ----------------------------------------------------------------- alert

	public static void renderAlert(GuiGraphics graphics, int screenW, int screenH,
			String name, int distance, double[] frame) {
		Minecraft mc = Minecraft.getInstance();
		if (graphics == null || mc == null || mc.font == null || frame == null || frame.length < 3) {
			return;
		}
		double fade = clamp(frame[0]);
		double pop = clamp(frame[1]);
		double pulse = clamp(frame[2]);
		if (fade <= 0.01) {
			return;
		}
		Font font = mc.font;
		drawVignette(graphics, screenW, screenH, fade, pulse);

		String person = name == null || name.isBlank() ? "SOMEONE" : name;
		String main = person + " IS WATCHING YOU";
		String second = distance >= 0 ? distance + " blocks away" : "nearby";
		float mainScale = 2.0f;
		int maxTextWidth = Math.max(40, screenW - 48);
		if (font.width(main) * mainScale > maxTextWidth) {
			mainScale = Math.max(0.8f, maxTextWidth / (float) font.width(main));
		}
		main = fit(font, main, maxTextWidth, mainScale);
		float secondScale = Math.max(0.85f, Math.min(1.25f, mainScale * 0.62f));
		second = fit(font, second, maxTextWidth, secondScale);
		int mainWidth = Math.max(1, Math.round(font.width(main) * mainScale));
		int secondWidth = Math.max(1, Math.round(font.width(second) * secondScale));
		int mainHeight = Math.max(1, Math.round(font.lineHeight * mainScale));
		int secondHeight = Math.max(1, Math.round(font.lineHeight * secondScale));
		int panelW = Math.min(Math.max(40, screenW - 24), Math.max(mainWidth, secondWidth) + PAD * 4);
		int panelH = mainHeight + 8 + secondHeight + PAD * 3;
		int centerX = screenW / 2;
		int centerY = screenH / 2;
		int panelX = centerX - panelW / 2;
		int panelY = centerY - panelH / 2;

		var pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(centerX, centerY);
		pose.scale((float) pop, (float) pop);
		pose.translate(-centerX, -centerY);
		graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH,
				withAlpha(0x100A0C, (int) Math.round(225 * fade)));
		graphics.fill(panelX, panelY, panelX + panelW, panelY + 2,
				withAlpha(RED, (int) Math.round(235 * fade)));
		graphics.fill(panelX, panelY + panelH - 2, panelX + panelW, panelY + panelH,
				withAlpha(RED, (int) Math.round(145 * fade)));
		drawScaled(graphics, font, main, centerX - mainWidth / 2, panelY + PAD,
				mainScale, withAlpha(0xFFE0E0, (int) Math.round(255 * fade)));
		drawScaled(graphics, font, second, centerX - secondWidth / 2,
				panelY + PAD + mainHeight + 8, secondScale,
				withAlpha(0xFFB0B0, (int) Math.round(225 * fade)));
		pose.popMatrix();
	}

	public static void renderFallback(GuiGraphics graphics, int screenW, int screenH, String text, double fade) {
		Minecraft mc = Minecraft.getInstance();
		if (graphics == null || mc == null || mc.font == null || text == null || text.isBlank()) {
			return;
		}
		int alpha = Math.round(255 * (float) clamp(fade));
		String fitted = fit(mc.font, text, Math.max(40, screenW - 48));
		int x = Math.max(4, (screenW - mc.font.width(fitted)) / 2);
		int y = screenH / 2;
		graphics.drawString(mc.font, fitted, x, y, withAlpha(RED, alpha), true);
	}

	private static void drawVignette(GuiGraphics graphics, int w, int h, double fade, double pulse) {
		int edge = Math.max(8, Math.min(w, h) / 10);
		int base = Math.round((float) ((0.14 + 0.34 * pulse) * fade * 255.0));
		for (int band = 0; band < 3; band++) {
			int thickness = Math.max(2, edge * (3 - band) / 3);
			int alpha = Math.max(0, base * (band + 1) / 3);
			int color = withAlpha(0xFF0000, alpha);
			graphics.fill(0, 0, w, thickness, color);
			graphics.fill(0, h - thickness, w, h, color);
			graphics.fill(0, 0, thickness, h, color);
			graphics.fill(w - thickness, 0, w, h, color);
		}
	}

	private static void drawScaled(GuiGraphics graphics, Font font, String text, int x, int y,
			float scale, int color) {
		var pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(scale, scale);
		graphics.drawString(font, text, 0, 0, color, true);
		pose.popMatrix();
	}

	private static int rowHeight(Font font) {
		return Math.max(14, font.lineHeight + ROW_GAP);
	}

	private static String fit(Font font, String text, int maxWidth) {
		if (text == null) {
			return "";
		}
		if (maxWidth <= 0 || font.width(text) <= maxWidth) {
			return text;
		}
		String out = text;
		while (out.length() > 1 && font.width(out + "...") > maxWidth) {
			out = out.substring(0, out.length() - 1);
		}
		return out + "...";
	}

	private static String fit(Font font, String text, int maxWidth, float scale) {
		if (text == null) {
			return "";
		}
		if (maxWidth <= 0 || font.width(text) * scale <= maxWidth) {
			return text;
		}
		String out = text;
		while (out.length() > 1 && font.width(out + "...") * scale > maxWidth) {
			out = out.substring(0, out.length() - 1);
		}
		return out + "...";
	}

	private static int withAlpha(int rgb, int alpha) {
		return ((Math.max(0, Math.min(255, alpha)) & 0xFF) << 24) | (rgb & 0xFFFFFF);
	}

	private static double clamp(double value) {
		return Math.max(0.0, Math.min(1.0, value));
	}
}
