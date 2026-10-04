package com.griefer.client.gui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Scrollbar: proportional draggable thumb with wheel support and smooth
 * eased scrolling. The thumb is a translucent glass pill with a specular rim,
 * so it belongs to the same material as everything around it.
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

		float ratio = trackH / (float) contentHeight;
		thumbH = Math.max(24, (int) (trackH * ratio));
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

	/** Called on press over the rail strip; starts a thumb drag. */
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

	/** The thumb is drawn at the given x, 3px wide. */
	public void setRailX(int x) {
		this.railX = x;
	}

	public void render(GuiGraphics g) {
		if (!usable) {
			return;
		}
		int w = 5;
		Ui.glassFill(g, railX, thumbY, w, thumbH, UiTheme.RADIUS_PILL, 0xB33C4859, 0xB3202833);
		Ui.glassEdge(g, railX, thumbY, w, thumbH, UiTheme.RADIUS_PILL, 0x59FFFFFF, 0x1AFFFFFF);
	}

	private void clampTarget() {
		if (maxScroll <= 0) {
			target = 0;
		} else {
			target = Math.max(0, Math.min(maxScroll, target));
		}
	}
}
