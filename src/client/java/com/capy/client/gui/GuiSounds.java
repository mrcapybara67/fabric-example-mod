package com.capy.client.gui;

import com.capy.client.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/**
 * Layered vanilla UI sounds. No audio files are copied from another client.
 *
 * <p>Each action gets its own event and envelope. The old implementation used
 * one click event for click, toggle and slider and only changed pitch, which is
 * why every action sounded like the same blip.</p>
 */
public final class GuiSounds {
	private static final float CLICK_VOLUME = 0.30f;
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
		play(SoundEvents.NOTE_BLOCK_HAT.value(), 1.7f, volume(0.09f));
	}

	public static void toggle(boolean on) {
		if (on) {
			play(SoundEvents.NOTE_BLOCK_PLING.value(), 1.35f, volume(0.22f));
			play(SoundEvents.AMETHYST_BLOCK_CHIME, 1.45f, volume(0.12f));
		} else {
			play(SoundEvents.UI_BUTTON_CLICK.value(), 0.72f, volume(0.24f));
			play(SoundEvents.NOTE_BLOCK_BASS.value(), 0.82f, volume(0.10f));
		}
	}

	public static void tab() {
		play(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.15f, volume(0.18f));
		play(SoundEvents.UI_BUTTON_CLICK.value(), 0.85f, volume(0.10f));
	}

	/** One quiet tick per step, never more than once every 60 ms. */
	public static void slider(double fraction) {
		long now = System.currentTimeMillis();
		if (now - lastSliderMs < 60L) {
			return;
		}
		lastSliderMs = now;
		float pitch = 0.8f + 0.7f * (float) Math.max(0.0, Math.min(1.0, fraction));
		play(SoundEvents.NOTE_BLOCK_HAT.value(), pitch, volume(0.16f));
	}

	public static void picker() {
		play(SoundEvents.AMETHYST_BLOCK_CHIME, 1.65f, volume(0.14f));
	}

	public static void open() {
		play(SoundEvents.UI_TOAST_IN, 1.12f, volume(0.28f));
		play(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.48f, volume(0.10f));
	}

	/** A rejected action, kept distinct from a successful click. */
	public static void error() {
		play(SoundEvents.VILLAGER_NO, 0.92f, volume(0.22f));
	}

	public static void close() {
		play(SoundEvents.UI_TOAST_OUT, 0.88f, volume(0.23f));
		play(SoundEvents.NOTE_BLOCK_BASS.value(), 0.72f, volume(0.08f));
	}

	private static void play(SoundEvent event, float pitch, float volume) {
		if (!enabled() || volume <= 0f || event == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.getSoundManager() != null) {
			mc.getSoundManager().play(SimpleSoundInstance.forUI(event, pitch, volume));
		}
	}
}
