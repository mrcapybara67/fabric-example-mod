package com.capy.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * {@link UiCanvas} backed by Minecraft's {@link GuiGraphics}. Scissor regions
 * are pushed and popped in pairs; rounded surfaces use generated sprites and
 * are tinted at draw time.
 */
public final class McCanvas implements UiCanvas {
	private static final int SRC = UiSprites.SRC_CORNER;
	private static final int SRC_CENTRE = UiSprites.SRC_CENTER;
	private static final int TEX = UiSprites.SIZE;
	private GuiGraphics graphics;
	private int clipDepth;
	private int draws;

	/** Reusable canvas; bind a new frame with {@link #bind(GuiGraphics)}. */
	public McCanvas() {
	}

	private McCanvas(GuiGraphics graphics) {
		this.graphics = graphics;
	}

	public static McCanvas of(GuiGraphics graphics) {
		return new McCanvas(graphics);
	}

	public McCanvas bind(GuiGraphics graphics) {
		this.graphics = graphics;
		this.clipDepth = 0;
		this.draws = 0;
		return this;
	}

	public GuiGraphics graphics() {
		return graphics;
	}


	@Override
	public int drawCalls() {
		return draws;
	}

	@Override
	public void fill(int x1, int y1, int x2, int y2, int argb) {
		if (x2 <= x1 || y2 <= y1) {
			return;
		}
		draws++;
		graphics.fill(x1, y1, x2, y2, argb);
	}

	@Override
	public void gradient(int x1, int y1, int x2, int y2, int top, int bottom) {
		if (x2 <= x1 || y2 <= y1) {
			return;
		}
		draws++;
		graphics.fillGradient(x1, y1, x2, y2, top, bottom);
	}

	@Override
	public void rounded(int x, int y, int w, int h, int radius, int color) {
		roundedKind(UiSprites.Kind.PANEL, x, y, w, h, radius, color);
	}

	@Override
	public void roundedKind(UiSprites.Kind kind, int x, int y, int w, int h, int radius, int color) {
		if (w <= 0 || h <= 0) {
			return;
		}
		if (radius <= 0) {
			fill(x, y, x + w, y + h, color);
			return;
		}
		// A nine-slice with no centre band would join four corner masks and
		// leave a cross-shaped seam through small pills, knobs and circles.
		// Those shapes use the per-row path, which is correct at any size.
		if (w <= radius * 2 + 1 || h <= radius * 2 + 1) {
			Ui.roundRectFallback(this, x, y, w, h, radius, color);
			return;
		}
		Identifier id = UiSprites.id(kind, radius);
		if (id == null) {
			Ui.roundRectFallback(this, x, y, w, h, radius, color);
			return;
		}
		nineSlice(id, x, y, w, h, radius, color);
	}

	@Override
	public void roundedRing(int x, int y, int w, int h, int radius, int top, int bottom) {
		Identifier id = UiSprites.id(UiSprites.Kind.BORDER, radius);
		if (id == null || w <= radius * 2 + 1 || h <= radius * 2 + 1) {
			Ui.surfaceEdgeFallback(this, x, y, w, h, radius, top, bottom);
			return;
		}
		nineSlice(id, x, y, w, h, radius, top);
	}

	@Override
	public void softShadow(int x, int y, int w, int h, int radius, int color) {
		Identifier id = UiSprites.id(UiSprites.Kind.SHADOW, radius);
		if (id == null || w <= radius * 2 + 1 || h <= radius * 2 + 1) {
			Ui.shadowFallback(this, x, y, w, h, radius);
			return;
		}
		nineSlice(id, x, y, w, h, radius, color);
	}

	@Override
	public void sheen(int x, int y, int w, int h, int radius, float phase) {
		if (!GuiQuality.current().sheen()) {
			return;
		}
		Identifier id = UiSprites.id(UiSprites.Kind.SHEEN, radius);
		if (id == null) {
			Ui.sheenFallback(this, x, y, w, h, radius, phase);
			return;
		}
		blit(id, x, y, 0f, 0f, w, h, TEX, TEX, 0x22FFFFFF);
	}

	@Override
	public void frost(int x, int y, int w, int h, int color) {
		if (!GuiQuality.current().noise()) {
			return;
		}
		Identifier id = UiSprites.id(UiSprites.Kind.NOISE, 6);
		if (id == null) {
			Ui.frostFallback(this, x, y, w, h, color);
			return;
		}
		blit(id, x, y, 0f, 0f, w, h, TEX, TEX, color);
	}

	@Override
	public void vignette(int width, int height, float fade) {
		Identifier id = UiSprites.id(UiSprites.Kind.VIGNETTE, 20);
		if (id == null) {
			Ui.vignetteFallback(this, width, height, fade);
			return;
		}
		int alpha = Math.round(90 * Ui.clamp01(fade));
		if (alpha <= 0) {
			return;
		}
		blit(id, 0, 0, 0f, 0f, width, height, TEX, TEX, alpha << 24);
	}

	private void nineSlice(Identifier id, int x, int y, int w, int h, int radius, int color) {
		int corner = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
		if (corner <= 0) {
			fill(x, y, x + w, y + h, color);
			return;
		}
		int centreW = w - corner * 2;
		int centreH = h - corner * 2;
		blit(id, x, y, 0, 0, corner, corner, SRC, SRC, color);
		blit(id, x + corner, y, SRC, 0, centreW, corner, SRC_CENTRE, SRC, color);
		blit(id, x + w - corner, y, TEX - SRC, 0, corner, corner, SRC, SRC, color);
		blit(id, x, y + corner, 0, SRC, corner, centreH, SRC, SRC_CENTRE, color);
		blit(id, x + corner, y + corner, SRC, SRC, centreW, centreH, SRC_CENTRE, SRC_CENTRE, color);
		blit(id, x + w - corner, y + corner, TEX - SRC, SRC, corner, centreH, SRC, SRC_CENTRE, color);
		blit(id, x, y + h - corner, 0, TEX - SRC, corner, corner, SRC, SRC, color);
		blit(id, x + corner, y + h - corner, SRC, TEX - SRC, centreW, corner, SRC_CENTRE, SRC, color);
		blit(id, x + w - corner, y + h - corner, TEX - SRC, TEX - SRC, corner, corner, SRC, SRC, color);
	}

	private void blit(Identifier id, int x, int y, float u, float v, int w, int h,
			int sourceW, int sourceH, int color) {
		if (w <= 0 || h <= 0 || sourceW <= 0 || sourceH <= 0) {
			return;
		}
		draws++;
		// The thirteen-argument overload separates the source region from the
		// destination rectangle. Without it the stretched centre sampled past
		// the right and bottom edges of the generated texture.
		graphics.blit(RenderPipelines.GUI_TEXTURED, id, x, y, u, v, w, h,
				sourceW, sourceH, TEX, TEX, color);
	}

	@Override
	public void pushClip(int x, int y, int width, int height) {
		if (width <= 0 || height <= 0) {
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
