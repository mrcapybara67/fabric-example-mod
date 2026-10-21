package com.capy.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Slider control: the label and its live value share one line, then a plum
 * track with an accent fill and a petal shaped knob.
 *
 * The value model is bound once (never per frame); geometry and display
 * strings are bound per frame by the owning card.
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
		Ui.drawEllipsized(g, font, label, rowX, trackY - 17,
				rowW - font.width(valueText) - UiTheme.SP_3, UiTheme.TEXT_2);
		Ui.drawRightAligned(g, font, valueText, rowX + rowW, trackY - 17, UiTheme.TEXT);

		int trackH = 8;
		int half = trackH / 2;
		int knobX = trackX0 + Math.round(fill.value() * (trackX1 - trackX0));

		// Plum track with a hairline rim.
		Ui.surface(g, trackX0, trackY - half, trackX1 - trackX0, trackH, UiTheme.RADIUS_PILL,
				UiTheme.TRACK_TOP, UiTheme.TRACK_BOTTOM);
		Ui.surfaceEdge(g, trackX0, trackY - half, trackX1 - trackX0, trackH, UiTheme.RADIUS_PILL,
				0x2EFFFFFF, 0x12FFFFFF);

		// Accent fill up to the knob.
		if (knobX > trackX0 + half) {
			int fw = knobX - trackX0;
			Ui.roundRect(g, trackX0, trackY - half - 1, fw, trackH + 2, UiTheme.RADIUS_PILL,
					Ui.withAlpha(UiTheme.ACCENT, 0x40));
			Ui.surface(g, trackX0, trackY - half, fw, trackH, UiTheme.RADIUS_PILL,
					UiTheme.ACCENT, UiTheme.ACCENT_DEEP);
			Ui.surfaceEdge(g, trackX0, trackY - half, fw, trackH, UiTheme.RADIUS_PILL,
					0x73FFFFFF, 0x2EFFFFFF);
		}

		// Knob: a small pale disc riding the track.
		int d = trackH + 6;
		int kx = knobX - d / 2;
		int ky = trackY - d / 2;
		Ui.roundRect(g, kx + 1, ky + 2, d, d, UiTheme.RADIUS_PILL, 0x59000000);
		Ui.surface(g, kx, ky, d, d, UiTheme.RADIUS_PILL, 0xFFFFF6FA, 0xFFE3C9D6);
		Ui.outline(g, kx, ky, d, d, UiTheme.RADIUS_PILL, 0xA6FFFFFF);
		Ui.innerBevel(g, kx, ky, d, d, UiTheme.RADIUS_PILL, 0x59FFFFFF, 0x33000000);
	}

	private void apply(double mx) {
		if (model == null) {
			return;
		}
		double f = (mx - trackX0) / (double) Math.max(1, trackX1 - trackX0);
		model.setFraction(f);
	}
}
