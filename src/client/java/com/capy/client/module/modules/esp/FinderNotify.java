package com.capy.client.module.modules.esp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * Per-module finder notices.
 *
 * <p>Two rules are enforced here instead of in every finder:</p>
 * <ul>
 *   <li>a toast is shown at most once every ten seconds for one module, so a
 *       scan that confirms twenty chunks at once cannot fill the screen;</li>
 *   <li>a chat message is written once per new flag, with no rate limit - the
 *       user asked for a line per flag, and chat already scrolls.</li>
 * </ul>
 *
 * <p>The clock is wall clock, not game ticks: a toast must not stack up while
 * the game is paused behind a menu.</p>
 */
public final class FinderNotify {
	/** Minimum gap between two toasts from the same module. */
	public static final long TOAST_INTERVAL_MS = 10_000L;

	private long lastToastMs = Long.MIN_VALUE / 2;

	/** True when a toast may be shown now; updates the timestamp when it is. */
	public boolean toast(Minecraft mc, String title, String detail) {
		if (mc == null) {
			return false;
		}
		long now = System.currentTimeMillis();
		if (!toastAllowed(lastToastMs, now)) {
			return false;
		}
		lastToastMs = now;
		try {
			mc.getToastManager().addToast(SystemToast.multiline(mc,
					SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
					Component.literal(title == null ? "Capy" : title),
					Component.literal(detail == null ? "" : detail)));
			return true;
		} catch (RuntimeException ignored) {
			// A toast is a courtesy; a graphics-less session must not lose the
			// flag over it.
			return false;
		}
	}

	/** Writes one chat line, if there is a chat to write to. */
	public void chat(Minecraft mc, String line) {
		if (mc == null || mc.gui == null || line == null || line.isBlank()) {
			return;
		}
		try {
			mc.gui.getChat().addMessage(Component.literal(line));
		} catch (RuntimeException ignored) {
			// Same rule as the toast: never lose a detection to a UI failure.
		}
	}

	/** The rate rule, split out so it can be tested without a client. */
	public static boolean toastAllowed(long lastMs, long nowMs) {
		return nowMs - lastMs >= TOAST_INTERVAL_MS;
	}
}
