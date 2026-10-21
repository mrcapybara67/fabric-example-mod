package com.capy.client.module.modules.antiadmin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plays the alert sound.
 *
 * A vanilla sound event is used on purpose: it ships with the game, needs no
 * extra asset and is guaranteed to be present on every installation. The
 * elder guardian curse matches the guardian themed overlay.
 */
public final class AlertSound {
	private static final Logger LOGGER = LoggerFactory.getLogger(AlertSound.class);

	/** The event used for the alert. */
	private static final SoundEvent EVENT = SoundEvents.ELDER_GUARDIAN_CURSE;
	/** Slightly raised so it cuts through the music. */
	private static final float PITCH = 1.35f;

	private AlertSound() {
	}

	/** Plays the alert sound once, if the client is able to. */
	public static void play() {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.getSoundManager() == null) {
			return;
		}
		try {
			mc.getSoundManager().play(SimpleSoundInstance.forUI(EVENT, PITCH));
		} catch (RuntimeException e) {
			// A missing or broken sound file must never take the client down.
			LOGGER.warn("Capy: could not play the Anti Admin alert sound ({}).", e.toString());
		}
	}
}
