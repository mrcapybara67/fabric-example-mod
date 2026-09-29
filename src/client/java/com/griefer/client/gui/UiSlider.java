package com.griefer.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Reusable slider control: label, live value, hairline track with animated
 * fill and a draggable knob. The caller feeds it a 0..1 fraction model
 * (via {@link SliderModel}) so it stays decoupled from module settings.
 */
public class UiSlider {
	/** Minimal value model the slider renders and drives. */
	public interface SliderModel {
		double fraction();

		void setFraction(double fraction);
	}

	private final Anim fill = new Anim(0f, 22f);
	private SliderModel model;
	private String label = "";
	private String valueText = "";
	private int trackX0;
	private int trackX1;
	private int trackY;
	private boolean dragging;

	/** Binds the value model once (never per frame). */
	public void bindModel(SliderModel model) {
		this.model = model;
	}

	/** Binds geometry + display strings for the current frame. */
	public void bind(String label, String valueText, int trackX0, int trackX1, int trackY) {
		this.label = label;
		this.valueText = valueText;
		this.trackX0 = trackX0;
		this.trackX1 = trackX1;
		this.trackY = trackY;
		this.fill.to((float) model.fraction(), 1.0f);
	}

	public boolean isDragging() {
		return dragging;
	}

	public void pressed(double mx) {
		dragging = true;
		apply(mx);
	}

	public void dragged(double mx) {
		if (dragging) {
			apply(mx);
		}
	}

	/** Ends any drag. Returns true if a drag was actually in progress. */
	public boolean released() {
		boolean was = dragging;
		dragging = false;
		return was;
	}

	/**
	 * Renders label + value line and the track. The row must already be
	 * scissor-clipped by the caller.
	 */
	public void render(GuiGraphics g, Font font, int rowX, int rowW) {
		Ui.drawEllipsized(g, font, label, rowX, trackY - 11, rowW - font.width(valueText) - UiTheme.SP_3, UiTheme.TEXT_SUB);
		Ui.drawRightAligned(g, font, valueText, rowX + rowW, trackY - 11, UiTheme.TEXT);

		int knobX = trackX0 + Math.round(fill.value() * (trackX1 - trackX0));

		// Track
		g.fill(trackX0, trackY, trackX1, trackY + 1, UiTheme.LINE_HI);
		// Fill
		if (knobX > trackX0) {
			g.fill(trackX0, trackY, knobX, trackY + 1, UiTheme.ACCENT);
		}
		// Knob
		g.fill(knobX - 1, trackY - 2, knobX + 1, trackY + 3, UiTheme.TEXT);
	}

	private void apply(double mx) {
		if (model == null) {
			return;
		}
		double f = (mx - trackX0) / (double) Math.max(1, trackX1 - trackX0);
		model.setFraction(f);
	}
}
