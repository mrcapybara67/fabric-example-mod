package com.capy.client.module.modules;

import com.capy.client.config.ConfigValues;
import com.capy.client.gui.McCanvas;
import com.capy.client.gui.Ui;
import com.capy.client.gui.UiCanvas;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.Category;
import com.capy.client.module.ChoiceSetting;
import com.capy.client.module.ColorSetting;
import com.capy.client.module.Configurable;
import com.capy.client.module.Module;
import com.capy.client.module.SliderSetting;
import com.capy.client.module.Tickable;
import com.google.gson.JsonObject;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.Item;

/**
 * Hand Aura — a bracket frame around each hand item, drawn in screen space.
 *
 * The frame is a HUD overlay rather than world geometry because the hands are
 * not in the world: the first person item is drawn after the world pass with its
 * own projection and its own bobbing. Bracketing it on screen is the only way to
 * have the frame follow the hand through a swing, a bob or a perspective change
 * without hooking the hand renderer itself.
 *
 * Corners are two axis-aligned arms each, so the whole frame is eight filled
 * rectangles — no line rasteriser, no texture, and crisp at every GUI scale.
 *
 * Two details make it feel alive rather than painted on:
 *
 *   <ul>
 *   <li>the pulse — a sine, a slow breathing curve or a rare flicker;</li>
 *   <li>the item flash — twelve ticks of full brightness whenever the held item
 *       changes, so swapping from a sword to a pickaxe reads as a new item
 *       rather than as the same frame with different contents.</li>
 *   </ul>
 */
public class HandAuraModule extends Module implements Configurable, Tickable {
	private static final int FLASH_TICKS = 12;

	private final ColorSetting mainColor = setting(new ColorSetting("main_color", 0xFFFF6EB4));
	private final ColorSetting offColor = setting(new ColorSetting("off_color", 0xFF00FFFF));
	private final SliderSetting thickness = setting(new SliderSetting("thickness", 15, 5, 40, 1));
	private final SliderSetting length = setting(new SliderSetting("length", 25, 5, 50, 2));
	private final SliderSetting size = setting(new SliderSetting("size", 10, 5, 25, 1));
	private final SliderSetting opacity = setting(new SliderSetting("opacity", 90, 0, 100, 2));
	private final BooleanSetting mainhand = setting(new BooleanSetting("mainhand", true));
	private final BooleanSetting offhand = setting(new BooleanSetting("offhand", true));
	private final BooleanSetting glow = setting(new BooleanSetting("glow", true));
	private final ChoiceSetting pulseStyle = setting(new ChoiceSetting("pulse_style", 0,
			"sine", "breath", "flicker"));

	/** Pulse clock in ticks. */
	private float time;
	private int mainFlash;
	private int offFlash;
	private Item lastMain;
	private Item lastOff;

	public HandAuraModule() {
		super(Category.VISUAL, "hand_aura");
	}

	/**
	 * Resets the flash, and nothing else.
	 *
	 * <p>The HUD element used to be added on enable and removed on disable. That
	 * put a write into a global registry inside a click handler, and a registry
	 * keyed by id cannot survive that: a disable that did not remove the entry
	 * (or an add that threw before the flag was set) left the id registered, and
	 * the next enable then threw on the duplicate - which crashed the game on the
	 * second toggle rather than on the first. The element is now registered once
	 * at startup like every other Capy HUD layer, and gated by
	 * {@link #isEnabled()} inside the renderer, so toggling is a pure state
	 * change and cannot diverge from what the registry holds.
	 */
	@Override
	protected void onToggle(boolean state) {
		mainFlash = 0;
		offFlash = 0;
		lastMain = null;
		lastOff = null;
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!isEnabled() || mc.player == null) {
			return;
		}
		if (mainFlash > 0) {
			mainFlash--;
		}
		if (offFlash > 0) {
			offFlash--;
		}
		Item main = mc.player.getMainHandItem().getItem();
		Item off = mc.player.getOffhandItem().getItem();
		if (lastMain != null && main != lastMain) {
			mainFlash = FLASH_TICKS;
		}
		lastMain = main;
		if (lastOff != null && off != lastOff) {
			offFlash = FLASH_TICKS;
		}
		lastOff = off;
	}

	// ------------------------------------------------------------------- hud

	/** HUD hook, registered once by the client entrypoint and gated below. */
	public void onHudRender(GuiGraphics graphics, DeltaTracker delta) {
		if (!isEnabled()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.options.hideGui) {
			return;
		}
		time += delta.getRealtimeDeltaTicks();

		float w = graphics.guiWidth();
		float h = graphics.guiHeight();
		// The slider is a percentage of the default frame, not a multiplier of
		// it: at its old reading, the default value of 10 made the frame 8.5
		// screen widths across and pushed both hands off the display entirely.
		// Dividing by the default keeps existing saved values meaning what they
		// meant (10 is the frame described below) and lets the slider run from
		// half to two and a half times that.
		float scale = (float) size.real() / 10f;
		float halfW = w * 0.046f * scale;
		float halfH = h * 0.088f * scale;
		UiCanvas canvas = McCanvas.of(graphics);

		// The hand positions are the item renderer's own: the first person item
		// sits low and towards the middle of the lower half of the screen, well
		// inside the hotbar row, and the off hand mirrors it. The frame is
		// centred on the middle of the item, not on the whole hand arc.
		if (mainhand.value() && !mc.player.getMainHandItem().isEmpty()) {
			drawHand(canvas, w * 0.74f, h * 0.81f, halfW, halfH, mainColor.argb(), mainFlash);
		}
		if (offhand.value() && !mc.player.getOffhandItem().isEmpty()) {
			drawHand(canvas, w * 0.26f, h * 0.81f, halfW, halfH, offColor.argb(), offFlash);
		}
	}

	private void drawHand(UiCanvas c, float cx, float cy, float halfW, float halfH, int rgb, int flash) {
		float pulse = flash > 0 ? 1f : pulse();
		float alpha = (float) opacity.real() * pulse;
		int argb = withAlpha(rgb, alpha);
		if (((argb >>> 24) & 0xFF) == 0) {
			return;
		}

		int centerX = Math.round(cx);
		int centerY = Math.round(cy);
		if (glow.value()) {
			int radius = Math.max(8, Math.round(Math.max(halfW, halfH) * 0.9f));
			softGlow(c, centerX, centerY, radius, withAlpha(rgb, alpha * 0.18f));
		}

		int t = Math.max(1, Math.round((float) thickness.real()));
		// Length is the share of an edge an arm covers, in percent. The old
		// reading multiplied the edge by the slider value itself, so the default
		// of 25 drew arms twenty-five times longer than the frame and the two
		// top arms met in the middle of the screen.
		float armShare = Math.max(0.02f, Math.min(0.5f, (float) length.real() * 0.01f));
		int armX = Math.max(t, Math.round(halfW * 2f * armShare));
		int armY = Math.max(t, Math.round(halfH * 2f * armShare));
		int x0 = Math.round(cx - halfW);
		int x1 = Math.round(cx + halfW);
		int y0 = Math.round(cy - halfH);
		int y1 = Math.round(cy + halfH);
		// Top left, top right, bottom left, bottom right: one arm along each
		// edge leaving every corner.
		c.fill(x0, y0, x0 + armX, y0 + t, argb);
		c.fill(x0, y0, x0 + t, y0 + armY, argb);
		c.fill(x1 - armX, y0, x1, y0 + t, argb);
		c.fill(x1 - t, y0, x1, y0 + armY, argb);
		c.fill(x0, y1 - t, x0 + armX, y1, argb);
		c.fill(x0, y1 - armY, x0 + t, y1, argb);
		c.fill(x1 - armX, y1 - t, x1, y1, argb);
		c.fill(x1 - t, y1 - armY, x1, y1, argb);
	}

	/**
	 * A radial fade built from concentric circles: every circle carries the same
	 * small share of the alpha, so the middle is covered by all of them and the
	 * rim by one.
	 */
	private static void softGlow(UiCanvas c, int cx, int cy, int radius, int argb) {
		int steps = 10;
		int share = Math.max(1, Math.round(((argb >>> 24) & 0xFF) / (float) steps));
		int color = (share << 24) | (argb & 0xFFFFFF);
		for (int i = steps; i >= 1; i--) {
			Ui.circle(c, cx, cy, Math.max(1, Math.round(radius * i / (float) steps)), color);
		}
	}

	private float pulse() {
		return switch (pulseStyle.option()) {
			case "breath" -> (float) Math.pow((Math.sin(time * Math.PI / 20.0) + 1.0) * 0.5, 2.2);
			case "flicker" -> flicker();
			default -> (float) (Math.sin(time * Math.PI / 10.0) * 0.3 + 0.7);
		};
	}

	/**
	 * A flicker that belongs to the tick rather than to the frame rate.
	 *
	 * Drawing straight from {@code Random} once per frame made the brackets
	 * flicker faster on a faster machine — nine times a second at 240 fps and
	 * twice at 60. The tick number is hashed instead, so the same second looks
	 * the same whatever the frame rate is.
	 */
	private float flicker() {
		int tick = (int) time;
		int hash = tick * 0x9E3779B9 ^ 0x7F4A7C15;
		hash ^= hash >>> 13;
		hash *= 0x85EBCA6B;
		hash ^= hash >>> 16;
		return (hash & 0xFF) < 26 ? 0.35f : 1f;
	}

	private static int withAlpha(int argb, float factor) {
		int alpha = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, factor)));
		return (alpha << 24) | (argb & 0xFFFFFF);
	}

	// ---------------------------------------------------------- persistence

	@Override
	public void loadConfig(JsonObject json) {
		mainColor.set(ConfigValues.intValue(json, "main_color", 0xFFFF6EB4));
		offColor.set(ConfigValues.intValue(json, "off_color", 0xFF00FFFF));
		thickness.set(ConfigValues.intValue(json, "thickness", 15));
		length.set(ConfigValues.intValue(json, "length", 25));
		size.set(ConfigValues.intValue(json, "size", 10));
		opacity.set(ConfigValues.intValue(json, "opacity", 90));
		mainhand.set(ConfigValues.boolValue(json, "mainhand", true));
		offhand.set(ConfigValues.boolValue(json, "offhand", true));
		glow.set(ConfigValues.boolValue(json, "glow", true));
		pulseStyle.select(ConfigValues.stringValue(json, "pulse_style", pulseStyle.option()));
	}

	@Override
	public void saveConfig(JsonObject json) {
		json.addProperty("main_color", mainColor.argb());
		json.addProperty("off_color", offColor.argb());
		json.addProperty("thickness", thickness.get());
		json.addProperty("length", length.get());
		json.addProperty("size", size.get());
		json.addProperty("opacity", opacity.get());
		json.addProperty("mainhand", mainhand.value());
		json.addProperty("offhand", offhand.value());
		json.addProperty("glow", glow.value());
		json.addProperty("pulse_style", pulseStyle.option());
	}
}
