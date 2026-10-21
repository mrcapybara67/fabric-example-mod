package com.capy.client.module;

import net.minecraft.client.Minecraft;

/**
 * Implemented by modules that need a per-tick update.
 *
 * The client tick loop calls {@link #onTick(Minecraft)} once per tick for
 * every module that implements this interface, so a module never has to
 * register its own listener.
 */
public interface Tickable {
	/** Called once per client tick, on the client thread. */
	void onTick(Minecraft mc);
}
