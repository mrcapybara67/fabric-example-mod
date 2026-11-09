package com.capy.client.gui;

import com.capy.client.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/** Small wrapper around vanilla UI sounds. No custom audio assets. */
public final class GuiSounds {
	private static final float CLICK_VOLUME = 0.35f;
	private static long lastSliderMs;

	private GuiSounds() {
	}

	private static boolean enabled() {
		return ClientConfig.get().guiSounds() && ClientConfig.get().guiVolume() > 0;
	}

	private static float volume(float base) {
		return base * (ClientConfig.get().guiVolume() / 100f);
	}

	public static void click() {
		play(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, volume(CLICK_VOLUME));
	}

	public static void toggle(boolean on) {
		play(SoundEvents.UI_BUTTON_CLICK.value(), on ? 1.22f : 0.82f, volume(CLICK_VOLUME));
	}

	/** One quiet tick per step, never more than once every 60 ms. */
	public static void slider(double fraction) {
		long now = System.currentTimeMillis();
		if (now - lastSliderMs < 60L) {
			return;
		}
		lastSliderMs = now;
		float pitch = 0.8f + 0.7f * (float) Math.max(0.0, Math.min(1.0, fraction));
		play(SoundEvents.UI_BUTTON_CLICK.value(), pitch, volume(0.18f));
	}

	public static void open() {
		play(SoundEvents.UI_TOAST_IN, 1.15f, volume(0.30f));
	}

	public static void close() {
		play(SoundEvents.UI_TOAST_OUT, 0.90f, volume(0.25f));
	}

	private static void play(net.minecraft.sounds.SoundEvent event, float pitch, float volume) {
		if (!enabled() || volume <= 0f) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.getSoundManager() != null) {
			mc.getSoundManager().play(SimpleSoundInstance.forUI(event, pitch, volume));
		}
	}
}
