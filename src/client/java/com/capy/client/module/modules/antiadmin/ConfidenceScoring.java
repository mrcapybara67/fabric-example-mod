package com.capy.client.module.modules.antiadmin;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The confidence ladder, kept free of Minecraft so it can be tested directly.
 *
 * <p>A probe hit is the server itself naming an account it hides, so it is the
 * top rung on its own. Everything else is scored by independent
 * {@link SignalFamily families}: several tab-hidden signals are still one
 * clue, while a tab-hidden signal plus a different family is genuinely more
 * than either alone.</p>
 */
public final class ConfidenceScoring {
	private ConfidenceScoring() {
	}

	/**
	 * Scores the confirmed kinds.
	 *
	 * @param counts    every counted kind and its observation count
	 * @param confirmed the kinds that reached their profile threshold
	 */
	public static Confidence score(Map<DetectionKind, Integer> counts, List<DetectionKind> confirmed) {
		if (counts.containsKey(DetectionKind.PROBE_CONFIRMED)) {
			return Confidence.CERTAIN;
		}
		Set<SignalFamily> families = EnumSet.noneOf(SignalFamily.class);
		boolean strong = false;
		for (DetectionKind kind : confirmed) {
			if (kind.family() != SignalFamily.NEUTRAL) {
				families.add(kind.family());
			}
			strong |= kind.strong();
		}
		if (families.size() >= 2) {
			return Confidence.HIGH;
		}
		if (families.size() == 1) {
			if (families.contains(SignalFamily.TAB_HIDDEN)) {
				// Several removal-shaped signals can all come from one normal
				// logout. Without a second family or a leave message they are a
				// weak clue, not a certainty.
				return Confidence.MEDIUM;
			}
			return strong ? Confidence.HIGH : Confidence.MEDIUM;
		}
		return Confidence.LOW;
	}
}
