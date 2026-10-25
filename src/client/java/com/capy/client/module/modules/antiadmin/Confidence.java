package com.capy.client.module.modules.antiadmin;

import java.util.Locale;

/**
 * How sure the correlator is about one subject.
 *
 * The palette is the one the HUD cards and the webhook share, so a red bar on
 * screen and a red embed in Discord always mean the same thing. The order of
 * the constants is the order of certainty, which is what {@link #atLeast}
 * compares.
 */
public enum Confidence {
	/** One weak signal, below its threshold: shown, never announced. */
	LOW(0xFFFFD54F),
	/** One weak signal confirmed, or several of the same kind. */
	MEDIUM(0xFFFFA726),
	/** Any strong signal, or two independent kinds. */
	HIGH(0xFFFF3B3B),
	/** A probe hit, or an unlisted entity plus anything else. */
	CERTAIN(0xFFB388FF);

	private final int argb;

	Confidence(int argb) {
		this.argb = argb;
	}

	/** Opaque colour used for bars, tags and embeds. */
	public int argb() {
		return argb;
	}

	public String label() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** True when this level is at least as certain as {@code other}. */
	public boolean atLeast(Confidence other) {
		return other == null || ordinal() >= other.ordinal();
	}

	/** Ranks a lower level into this one when {@code floor} is higher. */
	public Confidence atLeastOf(Confidence floor) {
		return floor != null && floor.ordinal() > ordinal() ? floor : this;
	}

	/** Parses a stored name, returning {@code fallback} for anything unknown. */
	public static Confidence parse(String name, Confidence fallback) {
		if (name != null) {
			for (Confidence level : values()) {
				if (level.name().equalsIgnoreCase(name)) {
					return level;
				}
			}
		}
		return fallback;
	}
}
