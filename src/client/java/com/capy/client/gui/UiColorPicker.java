package com.capy.client.gui;

import com.capy.client.module.ColorSetting;

/**
 * Colour control: one row with the current swatch and its hex value, and — when
 * opened — four gradient tracks for hue, saturation, brightness and alpha.
 *
 * The tracks are drawn as short runs of solid colour rather than a real
 * gradient, because the canvas only fills rectangles. A run is two pixels wide,
 * which is below the point where banding is visible at any GUI scale, and it
 * costs a few dozen fills per track — cheap enough to drag at full frame rate.
 *
 * Every track shows its own value against a background that *is* its range, so
 * the control explains itself without labels: the hue track runs through the
 * spectrum, the saturation track from grey to the current hue, the brightness
 * track from black to the current colour, and the alpha track fades to nothing
 * over a checkerboard.
 */
public class UiColorPicker {
	/** Height of the collapsed row. */
	public static final int ROW_H = 26;

	private static final int TRACK_H = 14;
	private static final int TRACK_GAP = 7;
	private static final int LABEL_W = 12;
	/** Height of the four tracks plus their padding. */
	public static final int OPEN_H = 4 * (TRACK_H + TRACK_GAP) + 2;

	private static final int HUE = 0;
	private static final int SATURATION = 1;
	private static final int BRIGHTNESS = 2;
	private static final int ALPHA = 3;

	private final ColorSetting setting;
	private final Anim hover = new Anim(0f, 14f);
	private String label = "Colour";
	private boolean open;
	/** Which track is being dragged, or -1. */
	private int dragTrack = -1;

	// Geometry of the most recent frame, for hit testing.
	private int x;
	private int y;
	private int w;
	private final int[] trackY = new int[4];

	public UiColorPicker(ColorSetting setting) {
		this.setting = setting;
	}

	public boolean isOpen() {
		return open;
	}

	public void setOpen(boolean open) {
		this.open = open;
	}

	/** Height this control needs in the current state. */
	public int height() {
		return open ? ROW_H + OPEN_H : ROW_H;
	}

	public void bind(int x, int y, int w, String label) {
		this.x = x;
		this.y = y;
		this.w = w;
		this.label = label;
	}

	public void render(UiCanvas c, UiText font, int mouseX, int mouseY, float delta) {
		boolean rowHovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + ROW_H;
		hover.to(rowHovered ? 1f : 0f, delta);

		// Row: label, hex, swatch.
		int swatch = ROW_H - 8;
		int sx = x + w - swatch;
		int sy = y + 4;
		Ui.drawEllipsized(c, font, label, x, Ui.centerTextY(y, ROW_H, font),
				Math.max(0, sx - x - font.width(setting.hex()) - UiTheme.SP_4 - UiTheme.SP_3),
				rowHovered ? UiTheme.TEXT : UiTheme.TEXT_2);
		Ui.drawRightAligned(c, font, setting.hex(), sx - UiTheme.SP_4,
				Ui.centerTextY(y, ROW_H, font), rowHovered ? UiTheme.TEXT : UiTheme.TEXT_3);

		// The swatch sits on a checkerboard so a translucent colour still reads
		// as translucent instead of as a washed out solid.
		checker(c, sx, sy, swatch, swatch);
		Ui.roundRect(c, sx, sy, swatch, swatch, UiTheme.RADIUS - 4, setting.argb());
		Ui.outline(c, sx, sy, swatch, swatch, UiTheme.RADIUS - 4,
				Ui.lerpColor(0x4DFFFFFF, 0xA6FFFFFF, hover.value()));

		if (!open) {
			return;
		}

		float[] hsv = setting.hsv();
		int trackX = x + LABEL_W;
		int trackW = w - LABEL_W;
		int ty = y + ROW_H + 2;
		for (int track = 0; track < 4; track++) {
			trackY[track] = ty;
			renderTrack(c, font, track, hsv, trackX, ty, trackW);
			ty += TRACK_H + TRACK_GAP;
		}
	}

	private void renderTrack(UiCanvas c, UiText font, int track, float[] hsv, int trackX, int trackY, int trackW) {
		String label = switch (track) {
			case HUE -> "H";
			case SATURATION -> "S";
			case BRIGHTNESS -> "V";
			default -> "A";
		};
		font.draw(c, label, x, Ui.centerTextY(trackY, TRACK_H, font), UiTheme.TEXT_3);

		// Base surface: everything the gradient does not cover reads as the
		// same plum groove the sliders use.
		Ui.surface(c, trackX, trackY, trackW, TRACK_H, UiTheme.RADIUS_PILL,
				UiTheme.TRACK_TOP, UiTheme.TRACK_BOTTOM);

		int steps = Math.max(2, Math.min(64, trackW / 2));
		float value = value(track, hsv);
		for (int i = 0; i < steps; i++) {
			int x0 = trackX + Math.round(i * trackW / (float) steps);
			int x1 = trackX + Math.round((i + 1) * trackW / (float) steps);
			if (x1 <= x0) {
				continue;
			}
			c.fill(x0, trackY, x1, trackY + TRACK_H, sample(track, hsv, (i + 0.5f) / steps));
		}
		Ui.surfaceEdge(c, trackX, trackY, trackW, TRACK_H, UiTheme.RADIUS_PILL, 0x2EFFFFFF, 0x14000000);

		// The knob: a pale bar with a dark rim, so it stays visible on the
		// brightest end of the brightness track.
		int kw = 5;
		int kx = trackX + Math.round(value * (trackW - kw));
		Ui.roundRect(c, kx - 1, trackY - 2, kw + 2, TRACK_H + 4, UiTheme.RADIUS_PILL, 0x66000000);
		Ui.roundRect(c, kx, trackY - 1, kw, TRACK_H + 2, UiTheme.RADIUS_PILL, 0xFFFFF6FA);
		Ui.outline(c, kx, trackY - 1, kw, TRACK_H + 2, UiTheme.RADIUS_PILL, 0xCC000000);
	}

	/** The colour a track shows at {@code t} in 0..1. */
	private int sample(int track, float[] hsv, float t) {
		return switch (track) {
			case HUE -> ColorSetting.toArgb(t * 360f, 1f, 1f, 255);
			case SATURATION -> ColorSetting.toArgb(hsv[0], t, Math.max(0.15f, hsv[2]), 255);
			case BRIGHTNESS -> ColorSetting.toArgb(hsv[0], hsv[1], t, 255);
			default -> ColorSetting.toArgb(hsv[0], hsv[1], hsv[2], Math.round(t * 255f));
		};
	}

	/** The current position of a track, in 0..1. */
	private float value(int track, float[] hsv) {
		return switch (track) {
			case HUE -> hsv[0] / 360f;
			case SATURATION -> hsv[1];
			case BRIGHTNESS -> hsv[2];
			default -> setting.alpha() / 255f;
		};
	}

	/** Click inside the control. Returns true when consumed. */
	public boolean mouseClicked(double mx, double my) {
		if (mx < x || mx >= x + w) {
			return false;
		}
		if (my >= y && my < y + ROW_H) {
			open = !open;
			return true;
		}
		if (!open) {
			return false;
		}
		for (int track = 0; track < 4; track++) {
			if (my >= trackY[track] - 2 && my < trackY[track] + TRACK_H + 2) {
				dragTrack = track;
				apply(track, mx);
				return true;
			}
		}
		return my < y + height();
	}

	public boolean mouseDragged(double mx) {
		if (dragTrack < 0) {
			return false;
		}
		apply(dragTrack, mx);
		return true;
	}

	/** Ends a drag. Returns true when one was in progress. */
	public boolean released() {
		boolean was = dragTrack >= 0;
		dragTrack = -1;
		return was;
	}

	private void apply(int track, double mx) {
		double fraction = (mx - (x + LABEL_W)) / Math.max(1, w - LABEL_W);
		float t = (float) Math.max(0.0, Math.min(1.0, fraction));
		float[] hsv = setting.hsv();
		switch (track) {
			case HUE -> setting.setHsv(t * 360f, hsv[1], hsv[2]);
			case SATURATION -> setting.setHsv(hsv[0], t, hsv[2]);
			case BRIGHTNESS -> setting.setHsv(hsv[0], hsv[1], t);
			default -> setting.setAlpha(Math.round(t * 255f));
		}
	}

	/** A checkerboard behind anything translucent. */
	private static void checker(UiCanvas c, int x, int y, int w, int h, int cell) {
		for (int row = 0; row * cell < h; row++) {
			for (int col = 0; col * cell < w; col++) {
				boolean dark = ((row + col) & 1) == 0;
				int x0 = x + col * cell;
				int y0 = y + row * cell;
				c.fill(x0, y0, Math.min(x + w, x0 + cell), Math.min(y + h, y0 + cell),
						dark ? 0xFF3A2F3B : 0xFF2A2029);
			}
		}
	}

	private static void checker(UiCanvas c, int x, int y, int w, int h) {
		checker(c, x, y, w, h, 4);
	}
}
