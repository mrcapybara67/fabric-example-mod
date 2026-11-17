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
	private static final int MAX_CLIPS = 32;
	private GuiGraphics graphics;
	private int clipDepth;
	private int draws;
	/** Logical-to-screen scale used by the ClickGUI size preset. */
	private float layoutScale = 1f;
	// Scissor rectangles in screen coordinates. A stack is required because a
	// card ripple clips inside the list clip; disabling the inner scissor used
	// to drop the outer one and let the card paint over the section header.
	private final int[] clipLeft = new int[MAX_CLIPS];
	private final int[] clipTop = new int[MAX_CLIPS];
	private final int[] clipRight = new int[MAX_CLIPS];
	private final int[] clipBottom = new int[MAX_CLIPS];

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
		this.layoutScale = 1f;
		return this;
	}

	/**
	 * Sets the uniform scale applied by the caller's pose. Geometry is already
	 * scaled by the pose; scissor rectangles are raw screen coordinates, so
	 * they are the one thing this canvas has to transform itself.
	 */
	public McCanvas layoutScale(float scale) {
		layoutScale = scale > 0f ? scale : 1f;
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
	public void texture(Identifier id, int x, int y, int w, int h, int sourceW, int sourceH, int color) {
		if (id == null || w <= 0 || h <= 0 || sourceW <= 0 || sourceH <= 0) {
			return;
		}
		draws++;
		graphics.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, w, h,
				sourceW, sourceH, sourceW, sourceH, color);
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
		int x1 = Math.round(x * layoutScale);
		int y1 = Math.round(y * layoutScale);
		int x2 = width <= 0 ? x1 : Math.round((x + width) * layoutScale);
		int y2 = height <= 0 ? y1 : Math.round((y + height) * layoutScale);
		if (clipDepth > 0) {
			x1 = Math.max(x1, clipLeft[clipDepth - 1]);
			y1 = Math.max(y1, clipTop[clipDepth - 1]);
			x2 = Math.min(x2, clipRight[clipDepth - 1]);
			y2 = Math.min(y2, clipBottom[clipDepth - 1]);
		}
		if (clipDepth >= MAX_CLIPS) {
			return;
		}
		clipLeft[clipDepth] = x1;
		clipTop[clipDepth] = y1;
		clipRight[clipDepth] = Math.max(x1, x2);
		clipBottom[clipDepth] = Math.max(y1, y2);
		clipDepth++;
		graphics.enableScissor(x1, y1, clipRight[clipDepth - 1], clipBottom[clipDepth - 1]);
	}

	@Override
	public void popClip() {
		if (clipDepth <= 0) {
			return;
		}
		clipDepth--;
		if (clipDepth == 0) {
			graphics.disableScissor();
		} else {
			graphics.enableScissor(clipLeft[clipDepth - 1], clipTop[clipDepth - 1],
					clipRight[clipDepth - 1], clipBottom[clipDepth - 1]);
		}
	}
}
