package com.capy.client.gui;

import com.capy.client.config.ClientConfig;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Last completed ClickGUI frame measurement, for the optional debug line and
 * for proving that a toggle does not remove cards from the list.
 */
public final class GuiPerf {
	private static final Logger LOGGER = LoggerFactory.getLogger("capy-gui");
	/** A toggle slower than this is reported even when the frame looks fine. */
	private static final double SLOW_TOGGLE_MS = 8.0;
	/** A card drop is only interesting while it is close to a toggle. */
	private static final long TOGGLE_DROP_WINDOW_NANOS = 1_000_000_000L;

	private static long started;
	private static double millis;
	private static int draws;
	private static int cardsThisFrame;
	private static int cardsDrawn;
	private static int lastCardsDrawn = -1;
	private static long toggleStarted;
	private static double toggleMillis;
	private static long lastToggleAt;
	private static String lastToggleId = "";
	private static String line = "GUI --";
	private static long formattedAt;

	private GuiPerf() {
	}

	public static void begin() {
		started = System.nanoTime();
		cardsThisFrame = 0;
	}

	/** Called once for every module card that actually reached the canvas. */
	public static void cardDrawn() {
		cardsThisFrame++;
	}

	public static void end(int drawCalls) {
		millis = (System.nanoTime() - started) / 1_000_000.0;
		draws = drawCalls;
		cardsDrawn = cardsThisFrame;
		long now = System.nanoTime();
		if (lastCardsDrawn >= 0 && cardsDrawn < lastCardsDrawn
				&& now - lastToggleAt <= TOGGLE_DROP_WINDOW_NANOS) {
			LOGGER.info("GUI cards drawn dropped {} -> {} on the frame after toggle '{}'",
					lastCardsDrawn, cardsDrawn, lastToggleId);
		}
		lastCardsDrawn = cardsDrawn;
	}

	/** Starts timing the click handler's toggle work. */
	public static void beginToggle(String moduleId) {
		toggleStarted = System.nanoTime();
		lastToggleId = moduleId == null ? "?" : moduleId;
	}

	/** Ends the timing and reports a toggle that blocked the render thread. */
	public static void endToggle() {
		long now = System.nanoTime();
		toggleMillis = (now - toggleStarted) / 1_000_000.0;
		lastToggleAt = now;
		if (toggleMillis > SLOW_TOGGLE_MS) {
			LOGGER.info("GUI toggle '{}' took {} ms on the render thread", lastToggleId,
					String.format(Locale.ROOT, "%.2f", toggleMillis));
		}
	}

	public static int cardsDrawn() {
		return cardsDrawn;
	}

	public static double toggleMillis() {
		return toggleMillis;
	}

	public static double millis() {
		return millis;
	}

	public static int draws() {
		return draws;
	}

	public static String line() {
		long now = System.nanoTime();
		if (ClientConfig.get().guiDebug() && now - formattedAt > 200_000_000L) {
			formattedAt = now;
			line = String.format(Locale.ROOT, "GUI %.2f ms / %d draws / %d cards / toggle %.2f ms",
					millis, draws, cardsDrawn, toggleMillis);
		}
		return line;
	}
}
