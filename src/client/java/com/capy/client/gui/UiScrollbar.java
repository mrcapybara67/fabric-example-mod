package com.capy.client.gui;


/**
 * Scrollbar: a proportional thumb with wheel support and smooth eased
 * scrollinc. The thumb is a slim accent-tinted pill so it belongs to the same
 * material as everything around it.
 */
public class UiScrollbar {
	private static final double WHEEL_PER_NOTCH = 26.0;

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
		// The thumb can never be taller than the track it rides: a short
		// viewport used to leave it hanging past the rail at the bottom.
		thumbH = Math.min(trackH, Math.max(24, (int) (trackH * ratio)));
		int thumbMax = Math.max(0, trackH - thumbH);
		thumbY = trackY + (maxScroll <= 0 ? 0
				: (int) Math.round((scroll / maxScroll) * thumbMax));
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

	/** The thumb is drawn at the given x, 4px wide. */
	public void setRailX(int x) {
		this.railX = x;
	}

	public void render(UiCanvas c) {
		if (!usable) {
			return;
		}
		int w = 4;
		// The rail is the whole travel, very faint. It tells the eye how much
		// of the list is on screen, which a floating thumb alone cannot.
		Ui.surface(c, railX, trackY, w, trackH, UiTheme.RADIUS_PILL, 0x14FFFFFF, 0x0DFFFFFF);
		// A soft bloom behind the thumb, so it reads as lit rather than painted.
		Ui.roundRect(c, railX - 2, thumbY - 2, w + 4, thumbH + 4, UiTheme.RADIUS_PILL,
				Ui.withAlpha(UiTheme.ACCENT, 0x1F));
		Ui.surface(c, railX, thumbY, w, thumbH, UiTheme.RADIUS_PILL,
				Ui.withAlpha(UiTheme.ACCENT, 0xB4), Ui.withAlpha(UiTheme.ACCENT_DEEP, 0xA0));
		Ui.surfaceEdge(c, railX, thumbY, w, thumbH, UiTheme.RADIUS_PILL, 0x4DFFFFFF, 0x1AFFFFFF);
	}

	private void clampTarget() {
		if (maxScroll <= 0) {
			target = 0;
		} else {
			target = Math.max(0, Math.min(maxScroll, target));
		}
	}
}
