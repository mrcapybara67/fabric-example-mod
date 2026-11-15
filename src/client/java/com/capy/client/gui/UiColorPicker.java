package com.capy.client.gui;

import com.capy.client.finder.core.ColorMath;
import com.capy.client.module.ColorSetting;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Colour control with a saturation/value square, hue and alpha bars, an
 * editable hex field, presets, recent colours and the animated colour modes.
 *
 * <p>The expensive gradients are generated textures: the square is one blit and
 * the two bars are one blit each. They are rebuilt only when the hue changes,
 * at most once every 30 ms, and released with the screen.</p>
 */
public class UiColorPicker {
	/** Height of the collapsed row. */
	public static final int ROW_H = 26;
	private static final int OPEN_H = 390;
	private static final int PAD = 8;
	private static final int HUE_H = 10;
	private static final int ALPHA_H = 10;
	private static final int SV_MAX = 120;
	private static final long TEXTURE_INTERVAL_MS = 30L;
	private static final AtomicInteger SEQUENCE = new AtomicInteger();

	private static final int[] PRESETS = {
			0xFFFFFFFF, 0xFFE8F4FF, 0xFFBFE7FF, 0xFF8FD3FF,
			0xFF4A9BD1, 0xFF2D6FA8, 0xFF7C5CFF, 0xFFFF5CA8,
			0xFFFF7A45, 0xFFFFD54A, 0xFF74F29A, 0xFF20D6C7,
			0xFFFF4D4D, 0xFFFF9EC4, 0xFFE0C3FF, 0xFFB8E0FF,
			0xFF9FE8CF, 0xFFFFE3A3, 0xFF8B3A3A, 0xFF2D4A7A,
			0xFF17202B, 0xFF3C4654, 0xFF8A96A6, 0xFF000000
	};

	private final ColorSetting setting;
	private final boolean showAlpha;
	private final Anim hover = new Anim(0f, 14f);
	private final UiTextField hexField = new UiTextField(10);
	private final int pickerId = SEQUENCE.incrementAndGet();
	private final float[] hsv = new float[3];

	private String label = "Colour";
	private boolean open;
	private boolean editingSecond;
	private boolean invalidHex;
	private int drag = -1;
	private static final int DRAG_SV = 1;
	private static final int DRAG_HUE = 2;
	private static final int DRAG_ALPHA = 3;
	private static final int DRAG_SPEED = 4;

	private int x;
	private int y;
	private int w;
	private int svX;
	private int svY;
	private int svSize;
	private int hueY;
	private int alphaY;
	private int hexY;
	private int copyX;
	private int copyW;
	private int presetY;
	private int recentY;
	private int modeY;
	private int speedY;
	private int previewY;
	private int swatchSize;
	private int secondX;
	private int secondY;

	private int cachedArgb;
	private String cachedHex = "";
	private double cachedSpeed;
	private String cachedSpeedText = "1.00x";
	private Identifier svTexture;
	private Identifier hueTexture;
	private Identifier alphaTexture;
	private float lastSvHue = Float.NaN;
	private int lastAlphaBase;
	private long svBuiltAt;
	private long alphaBuiltAt;
	private boolean textureFailed;

	public UiColorPicker(ColorSetting setting) {
		this(setting, true);
	}

	public UiColorPicker(ColorSetting setting, boolean showAlpha) {
		this.setting = setting;
		this.showAlpha = showAlpha;
		hexField.setPlaceholder("RRGGBB");
		hexField.onSubmit(this::commitHex);
		hexField.setFocused(false);
	}

	public boolean isOpen() {
		return open;
	}

	public void setOpen(boolean open) {
		this.open = open;
		if (!open) {
			hexField.setFocused(false);
			drag = -1;
		}
	}

	/** Height the open panel needs, excluding the collapsed row. */
	public int openHeight() {
		return OPEN_H;
	}

	public int height() {
		return open ? ROW_H + OPEN_H : ROW_H;
	}

	public void bind(int x, int y, int w, String label) {
		this.x = x;
		this.y = y;
		this.w = w;
		this.label = label == null || label.isBlank() ? "Colour" : label;
	}

	public void render(UiCanvas c, UiText font, int mouseX, int mouseY, float delta) {
		double seconds = System.nanoTime() / 1.0e9;
		boolean rowHovered = inside(mouseX, mouseY, x, y, w, ROW_H);
		hover.to(rowHovered ? 1f : 0f, delta);
		int base = setting.argb();
		int live = setting.argbAt(seconds);
		int swatch = ROW_H - 8;
		int sx = x + w - swatch;
		int sy = y + 4;

		String hex = hexText(base);
		Ui.drawEllipsized(c, font, label, x, Ui.centerTextY(y, ROW_H, font),
				Math.max(0, sx - x - font.width(hex) - UiTheme.SP_4 - UiTheme.SP_3),
				rowHovered ? UiTheme.TEXT : UiTheme.TEXT_2);
		Ui.drawRightAligned(c, font, hex, sx - UiTheme.SP_4,
				Ui.centerTextY(y, ROW_H, font), rowHovered ? UiTheme.TEXT : UiTheme.TEXT_3);
		checker(c, sx, sy, swatch, swatch, 5);
		Ui.roundRect(c, sx, sy, swatch, swatch, UiTheme.RADIUS - 4, live);
		Ui.outline(c, sx, sy, swatch, swatch, UiTheme.RADIUS - 4,
				Ui.lerpColor(0x4DFFFFFF, 0xA6FFFFFF, hover.value()));
		String modeText = modeShort(setting.mode());
		int modeWidth = font.width(modeText);
		if (modeWidth + 8 < w - swatch - 8) {
			Ui.drawRightAligned(c, font, modeText, x + w - swatch - 6,
					y + ROW_H + 3, UiTheme.TEXT_3);
		}

		if (!open) {
			return;
		}
		layout();
		ColorMath.hsvInto(activeArgb(), hsv);
		syncHexField();
		ensureTextures();

		int panelY = y + ROW_H;
		Ui.surface(c, x, panelY, w, OPEN_H - 2, UiTheme.RADIUS, UiTheme.WELL_TOP, UiTheme.WELL_BOTTOM);
		Ui.outline(c, x, panelY, w, OPEN_H - 2, UiTheme.RADIUS,
				Ui.withAlpha(UiTheme.BORDER, 0x4A));

		// Saturation / value square.
		if (svTexture != null) {
			c.texture(svTexture, svX, svY, svSize, svSize, 64, 64, 0xFFFFFFFF);
		} else {
			fallbackSquare(c, svX, svY, svSize, hsv[0]);
		}
		Ui.outline(c, svX, svY, svSize, svSize, 5, 0x66000000);
		int handleX = svX + Math.round(hsv[1] * (svSize - 1));
		int handleY = svY + Math.round((1f - hsv[2]) * (svSize - 1));
		Ui.circle(c, handleX, handleY, 5, 0xCC101418);
		Ui.ring(c, handleX, handleY, 5, 1, 0xFFFFFFFF);
		Ui.circle(c, handleX, handleY, 2, 0xFFFFFFFF);

		// Hue bar.
		if (hueTexture != null) {
			c.texture(hueTexture, svX, hueY, contentWidth(), HUE_H, 256, 1, 0xFFFFFFFF);
		} else {
			fallbackHue(c, svX, hueY, contentWidth(), HUE_H);
		}
		Ui.outline(c, svX, hueY, contentWidth(), HUE_H, 5, 0x66000000);
		int hueX = svX + Math.round(hsv[0] / 360f * (contentWidth() - 1));
		Ui.roundRect(c, hueX - 2, hueY - 2, 5, HUE_H + 4, 3, 0xD9101418);
		Ui.roundRect(c, hueX - 1, hueY - 1, 3, HUE_H + 2, 2, 0xFFFFFFFF);

		// Alpha bar over a checkerboard, in one cached texture.
		if (showAlpha) {
			if (alphaTexture != null) {
				c.texture(alphaTexture, svX, alphaY, contentWidth(), ALPHA_H, 256, 12, 0xFFFFFFFF);
			} else {
				checker(c, svX, alphaY, contentWidth(), ALPHA_H, 5);
				for (int i = 0; i < 16; i++) {
					int x0 = svX + i * contentWidth() / 16;
					int x1 = svX + (i + 1) * contentWidth() / 16;
					int a0 = Math.round(i / 16f * 255f);
					int a1 = Math.round((i + 1) / 16f * 255f);
					c.gradient(x0, alphaY, x1, alphaY + ALPHA_H,
							ColorMath.argb(a0, base >>> 16 & 255, base >>> 8 & 255, base & 255),
							ColorMath.argb(a1, base >>> 16 & 255, base >>> 8 & 255, base & 255));
				}
			}
			Ui.outline(c, svX, alphaY, contentWidth(), ALPHA_H, 5, 0x66000000);
			int alphaX = svX + Math.round(((activeArgb() >>> 24) / 255f) * (contentWidth() - 1));
			Ui.roundRect(c, alphaX - 2, alphaY - 2, 5, ALPHA_H + 4, 3, 0xD9101418);
			Ui.roundRect(c, alphaX - 1, alphaY - 1, 3, ALPHA_H + 2, 2, 0xFFFFFFFF);
		}

		// Hex field and copy button.
		int fieldW = Math.max(40, contentWidth() - copyW - 4);
		hexField.bind(svX, hexY, fieldW, 20);
		hexField.render(c, font, mouseX, mouseY, delta);
		if (invalidHex) {
			Ui.outline(c, svX, hexY, fieldW, 20, UiTheme.RADIUS, 0xFFFF5A5A);
		}
		boolean copyHover = inside(mouseX, mouseY, copyX, hexY, copyW, 20);
		Ui.surface(c, copyX, hexY, copyW, 20, 5,
				copyHover ? UiTheme.PANEL_ALT : UiTheme.TRACK_TOP,
				copyHover ? UiTheme.PANEL_TOP : UiTheme.TRACK_BOTTOM);
		Ui.outline(c, copyX, hexY, copyW, 20, 5, copyHover ? UiTheme.ACCENT : UiTheme.BORDER);
		Ui.drawCentered(c, font, "COPY", copyX + copyW / 2,
				Ui.centerTextY(hexY, 20, font), copyHover ? UiTheme.TEXT : UiTheme.TEXT_2);

		// Presets and recent colours.
		int columns = 12;
		int gap = Math.max(3, (contentWidth() - columns * 12) / Math.max(1, columns - 1));
		int cell = Math.max(9, Math.min(16, (contentWidth() - (columns - 1) * gap) / columns));
		for (int i = 0; i < PRESETS.length; i++) {
			int col = i % columns;
			int row = i / columns;
			int px = svX + col * (cell + gap);
			int py = presetY + row * (cell + 4);
			boolean hot = inside(mouseX, mouseY, px, py, cell, cell);
			Ui.roundRect(c, px, py, cell, cell, 3, PRESETS[i]);
			Ui.outline(c, px, py, cell, cell, 3,
					hot ? UiTheme.TEXT : Ui.withAlpha(0x000000, 0));
		}
		int recent = setting.recentCount();
		for (int i = 0; i < Math.min(8, recent); i++) {
			int px = svX + i * (cell + gap);
			boolean hot = inside(mouseX, mouseY, px, recentY, cell, cell);
			Ui.roundRect(c, px, recentY, cell, cell, 3, setting.recentAt(i));
			Ui.outline(c, px, recentY, cell, cell, 3,
					hot ? UiTheme.TEXT : Ui.withAlpha(0x000000, 0));
		}

		// Mode selector: four real buttons, not a dropdown.
		int modeGap = 4;
		int modeW = (contentWidth() - modeGap * 3) / 4;
		for (int i = 0; i < ColorMath.Mode.values().length; i++) {
			ColorMath.Mode mode = ColorMath.Mode.values()[i];
			int mx = svX + i * (modeW + modeGap);
			boolean selected = setting.mode() == mode;
			boolean hot = inside(mouseX, mouseY, mx, modeY, modeW, 20);
			Ui.surface(c, mx, modeY, modeW, 20, 5,
					selected ? Ui.withAlpha(UiTheme.ACCENT, 0x48) : hot ? UiTheme.PANEL_ALT : UiTheme.TRACK_TOP,
					selected ? Ui.withAlpha(UiTheme.ACCENT_DEEP, 0x36) : UiTheme.TRACK_BOTTOM);
			Ui.outline(c, mx, modeY, modeW, 20, 5,
					selected ? UiTheme.ACCENT : hot ? UiTheme.TEXT_3 : UiTheme.BORDER);
			Ui.drawCentered(c, font, modeLabel(mode, modeW), mx + modeW / 2,
					Ui.centerTextY(modeY, 20, font), selected ? UiTheme.ACCENT_2 : UiTheme.TEXT_2);
		}

		if (setting.mode() == ColorMath.Mode.GRADIENT) {
			boolean hot = inside(mouseX, mouseY, secondX, secondY, 18, 18);
			Ui.roundRect(c, secondX, secondY, 18, 18, 4, setting.second());
			Ui.outline(c, secondX, secondY, 18, 18, 4,
					editingSecond || hot ? UiTheme.ACCENT : UiTheme.BORDER);
			Ui.drawRightAligned(c, font, "2nd", secondX - 3,
					zeroHeight(font, secondY, 18), UiTheme.TEXT_3);
		}

		if (setting.mode() != ColorMath.Mode.STATIC) {
			Ui.drawEllipsized(c, font, "SPEED", svX, speedY - 2,
					Math.min(48, contentWidth()), UiTheme.TEXT_3);
			int trackX = svX + 50;
			int trackW = Math.max(20, contentWidth() - 50);
			int knob = trackX + Math.round((float) ((setting.speed() - 0.25) / 3.75) * trackW);
			c.fill(trackX, speedY + 2, trackX + trackW, speedY + 5, UiTheme.TRACK_BOTTOM);
			c.fill(trackX, speedY + 2, knob, speedY + 5,
					Ui.withAlpha(UiTheme.ACCENT, 0xCC));
			Ui.circle(c, knob, speedY + 3, 4, 0xFFDFF1FC);
			Ui.outline(c, trackX, speedY + 2, trackW, 4, 2, 0x55000000);
			Ui.drawRightAligned(c, font, speedText(), x + w - PAD, speedY - 2, UiTheme.TEXT_3);
		}

		// Live chip: the only place that shows the animated result inside the panel.
		int chipW = Math.max(70, contentWidth() - (setting.mode() == ColorMath.Mode.GRADIENT ? 96 : 0));
		Ui.roundRect(c, svX, previewY, chipW, 26, 7, 0x55000000);
		Ui.roundRect(c, svX + 1, previewY + 1, chipW - 2, 24, 6, live);
		Ui.outline(c, svX, previewY, chipW, 26, 7, Ui.withAlpha(UiTheme.BORDER, 0xAA));
		Ui.drawCentered(c, font, "LIVE", svX + chipW / 2,
				Ui.centerTextY(previewY, 26, font), contrast(live));
	}

	private void layout() {
		int contentX = x + PAD;
		int contentW = contentWidth();
		svX = contentX;
		svY = y + ROW_H + PAD;
		svSize = Math.min(SV_MAX, contentW);
		hueY = svY + svSize + 8;
		alphaY = hueY + HUE_H + 6;
		hexY = alphaY + (showAlpha ? ALPHA_H + 8 : 2);
		copyW = Math.min(38, Math.max(28, contentW / 5));
		copyX = x + w - PAD - copyW;
		presetY = hexY + 27;
		int columns = 12;
		int gap = Math.max(3, (contentW - columns * 12) / Math.max(1, columns - 1));
		swatchSize = Math.max(9, Math.min(16, (contentW - (columns - 1) * gap) / columns));
		recentY = presetY + swatchSize * 2 + 5;
		modeY = recentY + swatchSize + 10;
		speedY = modeY + 25;
		previewY = speedY + (setting.mode() == ColorMath.Mode.STATIC ? 2 : 18);
		secondX = x + w - PAD - 18;
		secondY = modeY + 1;
	}

	private int contentWidth() {
		return Math.max(40, w - PAD * 2);
	}

	private int activeArgb() {
		return editingSecond ? setting.second() : setting.argb();
	}

	private void setActiveArgb(int argb) {
		if (editingSecond) {
			setting.setSecond(argb);
		} else {
			setting.set(argb);
		}
		cachedArgb = 0;
	}

	private void syncHexField() {
		if (hexField.isFocused()) {
			return;
		}
		String next = ColorMath.hex(activeArgb(), false);
		if (!hexField.text().equals(next)) {
			hexField.setText(next);
		}
		invalidHex = false;
	}

	private String speedText() {
		if (Math.abs(cachedSpeed - setting.speed()) > 1.0e-9) {
			cachedSpeed = setting.speed();
			cachedSpeedText = String.format("%.2fx", cachedSpeed);
		}
		return cachedSpeedText;
	}

	private String hexText(int argb) {
		if (argb != cachedArgb || cachedHex.isEmpty()) {
			cachedArgb = argb;
			cachedHex = ColorMath.hex(argb, false);
		}
		return cachedHex;
	}

	private void commitHex() {
		String text = hexField.text();
		if (!ColorMath.validHex(text)) {
			invalidHex = true;
			return;
		}
		int parsed = ColorMath.parseHex(text, activeArgb());
		if (!showAlpha && (parsed >>> 24) == 0) {
			parsed |= 0xFF000000;
		}
		setActiveArgb(parsed);
		setting.remember(parsed);
		invalidHex = false;
	}

	/** Handles a click. Returns true when it was consumed. */
	public boolean mouseClicked(double mx, double my) {
		if (!inside(mx, my, x, y, w, height())) {
			return false;
		}
		if (inside(mx, my, x, y, w, ROW_H)) {
			open = !open;
			hexField.setFocused(false);
			drag = -1;
			GuiSounds.picker();
			return true;
		}
		if (!open) {
			return false;
		}
		if (inside(mx, my, svX, svY, svSize, svSize)) {
			drag = DRAG_SV;
			applySv(mx, my);
			return true;
		}
		if (inside(mx, my, svX, hueY, contentWidth(), HUE_H)) {
			drag = DRAG_HUE;
			applyHue(mx);
			return true;
		}
		if (showAlpha && inside(mx, my, svX, alphaY, contentWidth(), ALPHA_H)) {
			drag = DRAG_ALPHA;
			applyAlpha(mx);
			return true;
		}
		if (hexField.contains(mx, my)) {
			hexField.setFocused(true);
			hexField.setText(ColorMath.hex(activeArgb(), showAlpha));
			invalidHex = false;
			return true;
		}
		if (inside(mx, my, copyX, hexY, copyW, 20)) {
			copyToClipboard(ColorMath.hex(activeArgb(), showAlpha));
			GuiSounds.click();
			return true;
		}
		int columns = 12;
		int gap = Math.max(3, (contentWidth() - columns * 12) / Math.max(1, columns - 1));
		for (int i = 0; i < PRESETS.length; i++) {
			int px = svX + (i % columns) * (swatchSize + gap);
			int py = presetY + (i / columns) * (swatchSize + 4);
			if (inside(mx, my, px, py, swatchSize, swatchSize)) {
				setActiveArgb(withAlphaOf(PRESETS[i], activeArgb()));
				setting.remember(activeArgb());
				hexField.setFocused(false);
				GuiSounds.picker();
				return true;
			}
		}
		for (int i = 0; i < Math.min(8, setting.recentCount()); i++) {
			int px = svX + i * (swatchSize + gap);
			if (inside(mx, my, px, recentY, swatchSize, swatchSize)) {
				setActiveArgb(withAlphaOf(setting.recentAt(i), activeArgb()));
				setting.remember(activeArgb());
				hexField.setFocused(false);
				GuiSounds.picker();
				return true;
			}
		}
		int modeW = (contentWidth() - 12) / 4;
		for (int i = 0; i < ColorMath.Mode.values().length; i++) {
			int mx0 = svX + i * (modeW + 4);
			if (inside(mx, my, mx0, modeY, modeW, 20)) {
				setting.setMode(ColorMath.Mode.values()[i]);
				if (setting.mode() != ColorMath.Mode.GRADIENT) {
					editingSecond = false;
				}
				GuiSounds.tab();
				return true;
			}
		}
		if (setting.mode() == ColorMath.Mode.GRADIENT
				&& inside(mx, my, secondX, secondY, 18, 18)) {
			editingSecond = !editingSecond;
			GuiSounds.picker();
			return true;
		}
		if (setting.mode() != ColorMath.Mode.STATIC
				&& inside(mx, my, svX + 50, speedY - 3, Math.max(20, contentWidth() - 50), 12)) {
			drag = DRAG_SPEED;
			applySpeed(mx);
			return true;
		}
		hexField.setFocused(false);
		return true;
	}

	public boolean mouseDragged(double mx, double my) {
		if (drag < 0) {
			return false;
		}
		switch (drag) {
			case DRAG_SV -> applySv(mx, my);
			case DRAG_HUE -> applyHue(mx);
			case DRAG_ALPHA -> applyAlpha(mx);
			case DRAG_SPEED -> applySpeed(mx);
			default -> {
			}
		}
		return true;
	}

	/** Ends a drag. Returns true when one was in progress. */
	public boolean released() {
		boolean was = drag >= 0;
		if (was) {
			setting.remember(activeArgb());
		}
		drag = -1;
		return was;
	}

	public boolean charTyped(CharacterEvent event) {
		if (!open || !hexField.isFocused()) {
			return false;
		}
		if (hexField.charTyped(event)) {
			invalidHex = !hexField.text().isEmpty() && !ColorMath.validHex(hexField.text());
			return true;
		}
		return false;
	}

	public boolean keyPressed(KeyEvent event) {
		if (!open) {
			return false;
		}
		if (!hexField.isFocused()) {
			return false;
		}
		if ((event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0 && event.key() == GLFW.GLFW_KEY_V) {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.keyboardHandler != null) {
				String clip = mc.keyboardHandler.getClipboard();
				if (clip != null) {
					hexField.setText(clip.trim());
					invalidHex = !ColorMath.validHex(hexField.text());
				}
			}
			return true;
		}
		if (hexField.keyPressed(event)) {
			invalidHex = !hexField.text().isEmpty() && !ColorMath.validHex(hexField.text());
			return true;
		}
		return false;
	}

	public boolean hasTextInput() {
		return open && hexField.isFocused();
	}

	public void clearTextInput() {
		hexField.setFocused(false);
	}

	private void applySv(double mx, double my) {
		float sat = clamp01((float) ((mx - svX) / Math.max(1, svSize - 1)));
		float val = 1f - clamp01((float) ((my - svY) / Math.max(1, svSize - 1)));
		setActiveArgb(ColorMath.fromHsv(hsv[0], sat, val, activeArgb() >>> 24));
		GuiSounds.slider(sat);
	}

	private void applyHue(double mx) {
		float hue = clamp01((float) ((mx - svX) / Math.max(1, contentWidth() - 1))) * 360f;
		setActiveArgb(ColorMath.fromHsv(hue, hsv[1], hsv[2], activeArgb() >>> 24));
		GuiSounds.slider(hue / 360f);
	}

	private void applyAlpha(double mx) {
		int alpha = Math.round(clamp01((float) ((mx - svX) / Math.max(1, contentWidth() - 1))) * 255f);
		setActiveArgb((alpha << 24) | (activeArgb() & 0xFFFFFF));
		GuiSounds.slider(alpha / 255f);
	}

	private void applySpeed(double mx) {
		double fraction = clamp01((float) ((mx - (svX + 50)) / Math.max(20, contentWidth() - 50)));
		setting.setSpeed(0.25 + fraction * 3.75);
		GuiSounds.slider(fraction);
	}

	private void ensureTextures() {
		if (textureFailed) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.getTextureManager() == null) {
			return;
		}
		try {
			TextureManager manager = mc.getTextureManager();
			if (svTexture == null) {
				svTexture = Identifier.fromNamespaceAndPath("capy", "colorpicker/" + pickerId + "/sv");
			}
			if (hueTexture == null) {
				hueTexture = Identifier.fromNamespaceAndPath("capy", "colorpicker/" + pickerId + "/hue");
				upload(manager, hueTexture, hueImage());
			}
			if (alphaTexture == null) {
				alphaTexture = Identifier.fromNamespaceAndPath("capy", "colorpicker/" + pickerId + "/alpha");
			}
			long now = System.currentTimeMillis();
			if ((Float.isNaN(lastSvHue) || Math.abs(lastSvHue - hsv[0]) >= 0.25f)
					&& now - svBuiltAt >= TEXTURE_INTERVAL_MS) {
				upload(manager, svTexture, svImage(hsv[0]));
				lastSvHue = hsv[0];
				svBuiltAt = now;
			}
			int base = activeArgb() & 0xFFFFFF;
			if (showAlpha && (base != lastAlphaBase || manager.getTexture(alphaTexture) == null)
					&& now - alphaBuiltAt >= TEXTURE_INTERVAL_MS) {
				upload(manager, alphaTexture, alphaImage(base));
				lastAlphaBase = base;
				alphaBuiltAt = now;
			}
		} catch (RuntimeException | LinkageError failure) {
			textureFailed = true;
			releaseTextures();
		}
	}

	private static NativeImage svImage(float hue) {
		NativeImage image = new NativeImage(64, 64, false);
		int[] pixels = ColorMath.svSquare(hue, 64);
		for (int y = 0; y < 64; y++) {
			for (int x = 0; x < 64; x++) {
				image.setPixelABGR(x, y, abgr(pixels[y * 64 + x]));
			}
		}
		return image;
	}

	private static NativeImage hueImage() {
		NativeImage image = new NativeImage(256, 1, false);
		for (int x = 0; x < 256; x++) {
			image.setPixelABGR(x, 0, abgr(ColorMath.fromHsv(x / 256f * 360f, 1f, 1f, 255)));
		}
		return image;
	}

	private static NativeImage alphaImage(int rgb) {
		NativeImage image = new NativeImage(256, 12, false);
		for (int y = 0; y < 12; y++) {
			for (int x = 0; x < 256; x++) {
				int checker = (((x / 8) + (y / 6)) & 1) == 0 ? 0xFF3A3F47 : 0xFF252A31;
				int alpha = Math.round(x / 255f * 255f);
				int over = over((alpha << 24) | rgb, checker);
				image.setPixelABGR(x, y, abgr(over));
			}
		}
		return image;
	}

	private static void upload(TextureManager manager, Identifier id, NativeImage image) {
		if (manager.getTexture(id) != null) {
			manager.release(id);
		}
		manager.register(id, new DynamicTexture(() -> "capy-color-picker", image));
	}

	private void releaseTextures() {
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.getTextureManager() != null) {
			TextureManager manager = mc.getTextureManager();
			for (Identifier id : new Identifier[]{svTexture, hueTexture, alphaTexture}) {
				if (id != null && manager.getTexture(id) != null) {
					manager.release(id);
				}
			}
		}
		svTexture = null;
		hueTexture = null;
		alphaTexture = null;
		lastSvHue = Float.NaN;
		lastAlphaBase = 0;
	}

	/** Releases the dynamic textures with the screen. */
	public void close() {
		open = false;
		hexField.setFocused(false);
		drag = -1;
		releaseTextures();
	}

	private static void copyToClipboard(String text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.keyboardHandler != null) {
			mc.keyboardHandler.setClipboard(text);
		}
	}

	private static void fallbackSquare(UiCanvas c, int x, int y, int size, float hue) {
		int cells = 24;
		int step = Math.max(1, size / cells);
		for (int py = 0; py < size; py += step) {
			for (int px = 0; px < size; px += step) {
				int color = ColorMath.fromHsv(hue, clamp01(px / (float) size),
						1f - clamp01(py / (float) size), 255);
				c.fill(x + px, y + py, x + Math.min(size, px + step), y + Math.min(size, py + step), color);
			}
		}
	}

	private static void fallbackHue(UiCanvas c, int x, int y, int w, int h) {
		for (int i = 0; i < 6; i++) {
			int x0 = x + i * w / 6;
			int x1 = x + (i + 1) * w / 6;
			c.gradient(x0, y, x1, y + h,
					ColorMath.fromHsv(i * 60f, 1f, 1f, 255),
					ColorMath.fromHsv((i + 1) * 60f, 1f, 1f, 255));
		}
	}

	private static void checker(UiCanvas c, int x, int y, int w, int h, int cell) {
		for (int row = 0; row * cell < h; row++) {
			for (int col = 0; col * cell < w; col++) {
				int x0 = x + col * cell;
				int y0 = y + row * cell;
				c.fill(x0, y0, Math.min(x + w, x0 + cell), Math.min(y + h, y0 + cell),
						((row + col) & 1) == 0 ? 0xFF3A3F47 : 0xFF252A31);
			}
		}
	}

	private static int contrast(int argb) {
		return Ui.luminance(argb) > 0.55f ? 0xFF101820 : 0xFFFFFFFF;
	}

	private static int withAlphaOf(int rgb, int alphaSource) {
		return (alphaSource & 0xFF000000) | (rgb & 0xFFFFFF);
	}

	private static String modeLabel(ColorMath.Mode mode, int width) {
		if (width < 42) {
			return switch (mode) {
				case STATIC -> "SOL";
				case RAINBOW -> "RAIN";
				case PULSE -> "PUL";
				case GRADIENT -> "MIX";
			};
		}
		return switch (mode) {
			case STATIC -> "STATIC";
			case RAINBOW -> "RAINBOW";
			case PULSE -> "PULSE";
			case GRADIENT -> "GRADIENT";
		};
	}

	private static String modeShort(ColorMath.Mode mode) {
		return switch (mode) {
			case STATIC -> "";
			case RAINBOW -> "RAINBOW";
			case PULSE -> "PULSE";
			case GRADIENT -> "GRADIENT";
		};
	}

	private static int zeroHeight(UiText font, int top, int height) {
		return top + (height - font.lineHeight()) / 2;
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	private static float clamp01(float value) {
		return Math.max(0f, Math.min(1f, value));
	}

	private static int abgr(int argb) {
		return (argb & 0xFF000000) | ((argb & 0xFF) << 16) | (argb & 0xFF00) | ((argb >>> 16) & 0xFF);
	}

	private static int over(int fg, int bg) {
		int fa = fg >>> 24;
		int ba = bg >>> 24;
		int oa = fa + ba * (255 - fa) / 255;
		if (oa <= 0) {
			return 0;
		}
		int r = ((fg >> 16 & 255) * fa + (bg >> 16 & 255) * ba * (255 - fa) / 255) / oa;
		int g = ((fg >> 8 & 255) * fa + (bg >> 8 & 255) * ba * (255 - fa) / 255) / oa;
		int b = ((fg & 255) * fa + (bg & 255) * ba * (255 - fa) / 255) / oa;
		return ColorMath.argb(oa, r, g, b);
	}
}
