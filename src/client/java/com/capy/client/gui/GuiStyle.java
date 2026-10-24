package com.capy.client.gui;

/**
 * The looks the Click GUI can wear.
 *
 * The header carries a small button that cycles through this list. Only the
 * shape of the frame is style dependent; the cards, the settings and the hit
 * testing are the same in every style, so a new entry only has to answer
 * "what does the shell look like" and costs no changes anywhere else.
 *
 * Three entries exist today:
 *
 *   <ul>
 *     <li>{@link #NORMAL} - the decorated window: petals behind the frame, a
 *         drifting specular band, a lit top edge;</li>
 *     <li>{@link #MINIMAL} - the same layout with every moving decoration
 *         removed. It is both calmer to look at and cheaper to draw, which is
 *         what makes it useful on a low end machine;</li>
 *     <li>{@link #RADIAL} - the Sakura ring: a large accent disc carrying the
 *         wordmark, a category ring around it and a module sheet beside the
 *         ring. It has its own accent colour, separate from the window.</li>
 *   </ul>
 *
 * Further styles can be added here without touching the layout code.
 */
public enum GuiStyle {
	NORMAL("normal", "Normal", true),
	MINIMAL("minimal", "Minimal", false),
	RADIAL("radial", "Radial", true);

	private final String id;
	private final String label;
	private final boolean decorated;

	GuiStyle(String id, String label, boolean decorated) {
		this.id = id;
		this.label = label;
		this.decorated = decorated;
	}

	public String id() {
		return id;
	}

	public String label() {
		return label;
	}

	/** True when the frame draws its moving decoration (petals, sheen, glow). */
	public boolean decorated() {
		return decorated;
	}

	/** The style stored in the config, or {@link #NORMAL} if it is unknown. */
	public static GuiStyle byId(String id) {
		if (id != null) {
			for (GuiStyle style : values()) {
				if (style.id.equals(id)) {
					return style;
				}
			}
		}
		return NORMAL;
	}

	/** The next style, wrapping around; what the header button applies. */
	public GuiStyle next() {
		GuiStyle[] all = values();
		return all[(ordinal() + 1) % all.length];
	}
}
