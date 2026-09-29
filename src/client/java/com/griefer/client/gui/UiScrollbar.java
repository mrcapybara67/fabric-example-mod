package com.griefer.client.gui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Reusable scrollbar: proportional draggable thumb with wheel support and
 * smooth eased scrolling. The owner calls {@link #update} each frame with the
 * viewport geometry; wheel and drag input flow through here so scrolling
 * behavior stays consistent everywhere it is used.
 */
public class UiScrollbar {
	private static final double WHEEL_PER_NOTCH = 28.0;

	private double scroll;
	private double target;
	private double maxScroll;
	private boolean usable;

	private boolean dragging;
	private double dragStartMouseY;
	private double dragStartTarget;

	// Geometry for the current frame
	private int railX;
	private int trackY;
	private int trackH;
	private int thumbY;
	private int thumbH;

	/** Binds viewport geometry, advances smooth scrolling, clamps offsets. */
	public void update(int viewportTop, int viewportBottom, int contentHeight, float delta) {
		this.trackY = viewportTop;
		this.trackH = viewportBottom - viewportTop;
		this.usable = contentHeight > trackH && trackH > 0;
		if (!usable) {
			target = 0;
			scroll = 0;
			maxScroll = 0;
			return;
		}
		maxScroll = contentHeight - trackH;
		clampTarget();
		// Light exponential ease toward the target.
		scroll += (target - scroll) * (1.0 - Math.exp(-16.0 * delta));
		if (Math.abs(target - scroll) < 0.15) {
			scroll = target;
		}

		int maxThumb = trackH - (int) Math.max(24, trackH * (trackH / (float) contentHeight));
		thumbH = trackH - maxThumb;
		thumbH = Math.max(24, thumbH);
		int thumbMax = trackH - thumbH;
		thumbY = trackY + (int) Math.round((scroll / maxScroll) * thumbMax);
	}

	/** Current content offset in pixels (already smoothed). */
	public double scroll() {
		return scroll;
	}

	public boolean isDragging() {
		return dragging;
	}

	/** Wheel input. Returns true if this scrollbar consumed it. */
	public boolean onScroll(double vertical) {
		if (!usable) {
			return false;
		}
		target -= vertical * WHEEL_PER_NOTCH;
		clampTarget();
		return true;
	}

	/** Called on press anywhere over the rail; starts a thumb drag. */
	public boolean pressed(double my) {
		if (!usable) {
			return false;
		}
		dragging = true;
		dragStartMouseY = my;
		dragStartTarget = target;
		return true;
	}

	public void dragged(double my) {
		if (!dragging) {
			return;
		}
		int thumbTravel = Math.max(1, trackH - thumbH);
		double scale = maxScroll / (double) thumbTravel;
		target = dragStartTarget + (my - dragStartMouseY) * scale;
		clampTarget();
	}

	/** Ends any drag. Returns true if a drag was in progress. */
	public boolean released() {
		boolean was = dragging;
		dragging = false;
		return was;
	}

	/** The rail is drawn at the given x, 3px wide. */
	public void setRailX(int x) {
		this.railX = x;
	}

	public void render(GuiGraphics g) {
		if (!usable) {
			return;
		}
		g.fill(railX, trackY, railX + 3, trackY + trackH, UiTheme.LINE);
		g.fill(railX, thumbY, railX + 3, thumbY + thumbH, UiTheme.SURFACE_3);
	}

	private void clampTarget() {
		if (maxScroll <= 0) {
			target = 0;
		} else {
			target = Math.max(0, Math.min(maxScroll, target));
		}
	}
}
