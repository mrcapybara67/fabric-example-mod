package com.capy.client.gui;

import com.capy.client.config.ClientConfig;
import java.util.Locale;

/** Last completed ClickGUI frame measurement, for the optional debug line. */
public final class GuiPerf {
	private static long started;
	private static double millis;
	private static int draws;
	private static String line = "GUI --";
	private static long formattedAt;

	private GuiPerf() {
	}

	public static void begin() {
		started = System.nanoTime();
	}

	public static void end(int drawCalls) {
		millis = (System.nanoTime() - started) / 1_000_000.0;
		draws = drawCalls;
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
			line = String.format(Locale.ROOT, "GUI %.2f ms / %d draws", millis, draws);
		}
		return line;
	}
}
