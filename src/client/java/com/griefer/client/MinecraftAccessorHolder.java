package com.griefer.client;

import net.minecraft.client.Minecraft;

/**
 * Tiny indirection so mixins can read client globals without awkward casts
 * inside the mixin class itself.
 */
public final class MinecraftAccessorHolder {
	private MinecraftAccessorHolder() {
	}

	public static Minecraft client() {
		return Minecraft.getInstance();
	}

	public static net.minecraft.client.player.LocalPlayer player() {
		return Minecraft.getInstance().player;
	}
}
