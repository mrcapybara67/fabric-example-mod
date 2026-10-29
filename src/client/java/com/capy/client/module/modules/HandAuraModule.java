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
import java.util.Random;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
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
	private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath("capy", "hand_aura");
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

	private final Random random = new Random();
	/** Pulse clock in ticks. */
	private float time;
	private int mainFlash;
	private int offFlash;
	private Item lastMain;
	private Item lastOff;
	private boolean registered;

	public HandAuraModule() {
		super(Category.VISUAL, "hand_aura");
	}

	@Override
	protected void onToggle(boolean state) {
		mainFlash = 0;
		offFlash = 0;
		lastMain = null;
		lastOff = null;
		if (state) {
			if (!registered) {
				HudElementRegistry.addLast(HUD_ID, this::renderHud);
				registered = true;
			}
			return;
		}
		if (registered) {
			HudElementRegistry.removeElement(HUD_ID);
			registered = false;
		}
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

	private void renderHud(GuiGraphics graphics, DeltaTracker delta) {
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
		float sizeValue = (float) size.real();
		float halfW = w * 0.085f * sizeValue;
		float halfH = h * 0.155f * sizeValue;
		UiCanvas canvas = McCanvas.of(graphics);

		if (mainhand.value() && !mc.player.getMainHandItem().isEmpty()) {
			drawHand(canvas, w * 0.695f, h * 0.76f, halfW, halfH, mainColor.argb(), mainFlash);
		}
		if (offhand.value() && !mc.player.getOffhandItem().isEmpty()) {
			drawHand(canvas, w * 0.305f, h * 0.76f, halfW, halfH, offColor.argb(), offFlash);
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
		int armX = Math.max(t, Math.round(halfW * 2f * (float) length.real()));
		int armY = Math.max(t, Math.round(halfH * 2f * (float) length.real()));
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
			case "flicker" -> random.nextFloat() < 0.04f ? 0.35f : 1f;
			default -> (float) (Math.sin(time * Math.PI / 10.0) * 0.3 + 0.7);
		};
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
