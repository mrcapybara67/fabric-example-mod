package com.griefer.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Slider control: label + live value on the label line, then a rounded
 * background track with an accent fill and a dot knob. The value model is
 * bound once (never per frame); geometry and display strings are bound per
 * frame by the owning card.
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

	public void bindModel(SliderModel model) {
		this.model = model;
	}

	public void bind(String label, String valueText, int trackX0, int trackX1, int trackY) {
		this.label = label;
		this.valueText = valueText;
		this.trackX0 = trackX0;
		this.trackX1 = trackX1;
		this.trackY = trackY;
		this.fill.to((float) model.fraction(), 1.0f);
	}

	public boolean isDragging() {
		return sliderDrag() || dragging;
	}

	private boolean sliderDrag() {
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

	/** Ends any drag. Returns true if a drag was in progress. */
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
		Ui.drawEllipsized(g, font, label, rowX, trackY - 12, rowW - font.width(valueText) - UiTheme.SP_3, UiTheme.TEXT_2);
		Ui.drawRightAligned(g, font, valueText, rowX + rowW, trackY - 12, UiTheme.TEXT);

		int trackH = 5;
		int half = trackH / 2;
		int knobX = trackX0 + Math.round(fill.value() * (trackX1 - trackX0));

		// Track background (rounded, spans the full row)
		Ui.roundRect(g, trackX0, trackY - half, trackX1 - trackX0, trackH, UiTheme.RADIUS_PILL, UiTheme.SURFACE);
		// Accent fill up to the knob
		if (knobX > trackX0 + half) {
			Ui.roundRect(g, trackX0, trackY - half, knobX - trackX0, trackH, UiTheme.RADIUS_PILL, UiTheme.ACCENT);
		}
		// Knob: white dot, sits on top of the track edge
		Ui.roundRect(g, knobX - half - 1, trackY - half - 1, trackH + 2, trackH + 2, UiTheme.RADIUS_PILL, UiTheme.TEXT);
	}

	private void apply(double mx) {
		if (model == null) {
			return;
		}
		double f = (mx - trackX0) / (double) Math.max(1, trackX1 - trackX0);
		model.setFraction(f);
	}
}
