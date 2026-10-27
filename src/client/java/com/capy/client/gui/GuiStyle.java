package com.capy.client.gui;

/**
 * The looks the Click GUI can wear.
 *
 * The header carries a small button that cycles through this list. Only the
 * shape of the frame is style dependent; the cards, the settings and the hit
 * testing are the same in every style, so a new entry only has to answer
 * "what does the shell look like" and costs no changes anywhere else.
 *
 * Two entries exist today:
 *
 *   <ul>
 *     <li>{@link #NORMAL} - the decorated window: petals behind the frame, a
 *         drifting specular band, a lit top edge;</li>
 *     <li>{@link #RADIAL} - the Sakura ring: a large accent disc carrying the
 *         wordmark, a category ring around it and a module sheet beside the
 *         ring. It has its own accent colour, separate from the window.</li>
 *   </ul>
 *
 * Both styles draw their moving decoration, so nothing in the frame is gated
 * on the style: the window styles differ only in where the content lives.
 */
public enum GuiStyle {
	NORMAL("normal", "Normal"),
	RADIAL("radial", "Radial");

	private final String id;
	private final String label;

	GuiStyle(String id, String label) {
		this.id = id;
		this.label = label;
	}

	public String id() {
		return id;
	}

	public String label() {
		return label;
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

	/**
	 * The other style; what the header button applies.
	 *
	 * With two entries this is a toggle: NORMAL to RADIAL and back. The
	 * modulo keeps the arithmetic correct if a third style is ever added, so
	 * the button does not have to change with it.
	 */
	public GuiStyle next() {
		GuiStyle[] all = values();
		return all[(ordinal() + 1) % all.length];
	}
}
