package com.capy.client.finder.core;

/** User-selected flag quality. Auto-simplification is separate and never edits this. */
public enum FlagQuality {
	HIGH, BALANCED, LOW;

	public static FlagQuality parse(String id) {
		if (id != null) {
			for (FlagQuality quality : values()) {
				if (quality.name().equalsIgnoreCase(id)) {
					return quality;
				}
			}
		}
		return HIGH;
	}
}
