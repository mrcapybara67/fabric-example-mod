package com.capy.client.gui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Design tokens for the Capy Client GUI.
 *
 * The visual language is "Sakura": a dark, warm plum canvas where pink is the
 * <em>accent</em>, never the background. Every surface is a slightly different
 * value of the same plum hue, so the interface reads as one continuous sheet
 * of tinted paper rather than a pile of coloured boxes.
 *
 * Three palettes ship with the client and the user can switch between them
 * from the GUI (the choice is persisted by {@code ClientConfig}):
 *
 *   <ul>
 *     <li>{@code sakura} — the default: warm plum surfaces, soft pink accent.</li>
 *     <li>{@code sakura_deep} — darker canvas and a hotter pink, for players
 *         who want maximum contrast against a bright world.</li>
 *     <li>{@code sakura_blush} — softer, lighter and pinker, a calmer sheet.</li>
 *   </ul>
 *
 * Colours are plain ARGB ints so they can be blended and alpha-scaled without
 * allocating. The palette fields are mutable because the active palette is
 * swapped at runtime when the user changes the theme; they are only ever
 * written from the client thread during configuration load.
 */
public final class UiTheme {
	private UiTheme() {
	}

	// ------------------------------------------------------------------ palette

	/** One complete colour set. */
	public record Palette(
			String id,
			String label,
			int scrim,
			int shellTop,
			int shellBottom,
			int panelTop,
			int panelBottom,
			int panelAlt,
			int cardTop,
			int cardBottom,
			int cardHoverTop,
			int cardHoverBottom,
			int wellTop,
			int wellBottom,
			int trackTop,
			int trackBottom,
			int border,
			int accent,
			int accentSoft,
			int accentDeep,
			int accentGlow,
			int blossom,
			int paleCherry,
			int text,
			int textSecondary,
			int textMuted,
			int placeholder,
			int onAccent
	) {
	}

	private static final Palette SAKURA = new Palette(
			"sakura",
			"Sakura",
			0x8C0A0710,
			0xFF221B28,
			0xFF171220,
			0xFF2B2231,
			0xFF231B29,
			0xFF332838,
			0xFF322839,
			0xFF281F2E,
			0xFF3D3147,
			0xFF302639,
			0xFF1D1624,
			0xFF17111C,
			0xFF241D2B,
			0xFF1D1624,
			0xFF463342,
			0xFFFF8FB8,
			0xFFFFC4D8,
			0xFFD9578B,
			0x33FF8FB8,
			0xFFFFD1E1,
			0xFFFFEAF1,
			0xFFFFF4F8,
			0xFFBBAAB7,
			0xFF8A7A88,
			0xFF7B6B7A,
			0xFF3A0F22
	);

	private static final Palette SAKURA_DEEP = new Palette(
			"sakura_deep",
			"Deep Sakura",
			0x9A06050C,
			0xFF1A1522,
			0xFF0D0A11,
			0xFF221B2B,
			0xFF1A1523,
			0xFF2A2133,
			0xFF282031,
			0xFF1E1928,
			0xFF362A44,
			0xFF2A2135,
			0xFF171120,
			0xFF120E18,
			0xFF20182A,
			0xFF181222,
			0xFF3B2B3A,
			0xFFFF74A8,
			0xFFFFB3D2,
			0xFFC23A70,
			0x33FF74A8,
			0xFFFFC2DC,
			0xFFFFE2EE,
			0xFFFFF4F8,
			0xFFBBAAB7,
			0xFF877787,
			0xFF786879,
			0xFF36091F
	);

	private static final Palette SAKURA_BLUSH = new Palette(
			"sakura_blush",
			"Blush Sakura",
			0x8A140A14,
			0xFF2B2230,
			0xFF1F1824,
			0xFF342939,
			0xFF2A2130,
			0xFF3C3044,
			0xFF3A2D40,
			0xFF302636,
			0xFF473A52,
			0xFF392C3F,
			0xFF241C2C,
			0xFF1D1626,
			0xFF2A2130,
			0xFF221A29,
			0xFF513C4D,
			0xFFFFA0C4,
			0xFFFFD3E4,
			0xFFE0699A,
			0x33FFA0C4,
			0xFFFFD8E8,
			0xFFFFEFF5,
			0xFFFFF6FA,
			0xFFC7B4C2,
			0xFF95838F,
			0xFF857381,
			0xFF401327
	);

	private static final Map<String, Palette> PALETTES = new LinkedHashMap<>();

	static {
		for (Palette palette : List.of(SAKURA, SAKURA_DEEP, SAKURA_BLUSH)) {
			PALETTES.put(palette.id(), palette);
		}
	}

	private static Palette active = SAKURA;

	/** The palette currently in use. */
	public static Palette palette() {
		return active;
	}

	/** All selectable palettes, in display order. */
	public static List<Palette> palettes() {
		return List.copyOf(PALETTES.values());
	}

	// ---- Backdrop ---------------------------------------------------------
	/** Dims and tints the world behind the GUI so the panels have a base. */
	public static int SCRIM = active.scrim();

	// ---- Surfaces (top -> bottom gradient pairs) -------------------------
	/** Main window shell. */
	public static int SHELL_TOP = active.shellTop();
	public static int SHELL_BOTTOM = active.shellBottom();
	/** Sidebar, search field, header band. */
	public static int PANEL_TOP = active.panelTop();
	public static int PANEL_BOTTOM = active.panelBottom();
	/** Secondary surface: chips, dropdown list, badges. */
	public static int PANEL_ALT = active.panelAlt();
	/** Module cards. */
	public static int CARD_TOP = active.cardTop();
	public static int CARD_BOTTOM = active.cardBottom();
	/** Module cards under the pointer. */
	public static int CARD_HOVER_TOP = active.cardHoverTop();
	public static int CARD_HOVER_BOTTOM = active.cardHoverBottom();
	/** Inset well holding a module's settings. */
	public static int WELL_TOP = active.wellTop();
	public static int WELL_BOTTOM = active.wellBottom();
	/** Slider / toggle tracks. */
	public static int TRACK_TOP = active.trackTop();
	public static int TRACK_BOTTOM = active.trackBottom();

	// ---- Lines ------------------------------------------------------------
	/** Hairline border colour. */
	public static int BORDER = active.border();
	/** Subtle top highlight on a surface. */
	public static int HIGHLIGHT = 0x1FFFFFFF;
	/** Bottom inner shade, gives the surface depth. */
	public static int SHADE = 0x3A000000;

	// ---- Accent -----------------------------------------------------------
	/** Sakura pink — used for active, enabled and focus. */
	public static int ACCENT = active.accent();
	/** Cherry blossom — hover states and secondary highlights. */
	public static int ACCENT_2 = active.accentSoft();
	/** Deep rose — pressed states and warning text. */
	public static int ACCENT_DEEP = active.accentDeep();
	/** Very low alpha accent for washes and bloom. */
	public static int ACCENT_GLOW = active.accentGlow();
	/** Blossom highlight - the lightest pink, used on fine details. */
	public static int BLOSSOM = active.blossom();
	/** Pale cherry - a near white pink for the brightest highlights. */
	public static int PALE_CHERRY = active.paleCherry();

	// ---- Text -------------------------------------------------------------
	public static int TEXT = active.text();
	public static int TEXT_2 = active.textSecondary();
	public static int TEXT_3 = active.textMuted();
	public static int PLACEHOLDER = active.placeholder();
	/** Text drawn on top of the accent colour. */
	public static int ON_ACCENT = active.onAccent();

	// ---- Shadows ----------------------------------------------------------
	public static int SHADOW_WIDE = 0x4D000000;
	public static int SHADOW_MID = 0x38000000;
	public static int SHADOW_TIGHT = 0x44000000;

	// ---- Spacing scale (2 / 4 / 6 / 8 / 12 / 16 / 20 / 24) ---------------
	public static final int SP_1 = 2;
	public static final int SP_2 = 4;
	public static final int SP_3 = 6;
	public static final int SP_4 = 8;
	public static final int SP_5 = 12;
	public static final int SP_6 = 16;
	public static final int SP_7 = 20;
	public static final int SP_8 = 24;

	// ---- Radii ------------------------------------------------------------
	public static final int RADIUS = 10;      // controls
	public static final int RADIUS_LG = 14;   // inner panels
	public static final int RADIUS_XL = 20;   // window shell
	public static final int RADIUS_PILL = 99; // clamped by the drawing layer

	// ---- Layout -----------------------------------------------------------
	public static final int HEADER_H = 58;
	public static final int SIDEBAR_W = 190;
	public static final int CARD_H = 54;
	public static final int CARD_GAP = 10;
	public static final int TOGGLE_W = 40;
	public static final int TOGGLE_H = 22;
	public static final int SEARCH_W = 190;
	public static final int SEARCH_H = 30;
	public static final int CLOSE_BTN = 26;
	public static final int CATEGORY_H = 34;

	/** Copies the palette values into the mutable token fields above. */
	private static void refresh() {
		SCRIM = active.scrim();
		SHELL_TOP = active.shellTop();
		SHELL_BOTTOM = active.shellBottom();
		PANEL_TOP = active.panelTop();
		PANEL_BOTTOM = active.panelBottom();
		PANEL_ALT = active.panelAlt();
		CARD_TOP = active.cardTop();
		CARD_BOTTOM = active.cardBottom();
		CARD_HOVER_TOP = active.cardHoverTop();
		CARD_HOVER_BOTTOM = active.cardHoverBottom();
		WELL_TOP = active.wellTop();
		WELL_BOTTOM = active.wellBottom();
		TRACK_TOP = active.trackTop();
		TRACK_BOTTOM = active.trackBottom();
		BORDER = active.border();
		ACCENT = active.accent();
		ACCENT_2 = active.accentSoft();
		ACCENT_DEEP = active.accentDeep();
		ACCENT_GLOW = active.accentGlow();
		BLOSSOM = active.blossom();
		PALE_CHERRY = active.paleCherry();
		TEXT = active.text();
		TEXT_2 = active.textSecondary();
		TEXT_3 = active.textMuted();
		PLACEHOLDER = active.placeholder();
		ON_ACCENT = active.onAccent();
	}

	static {
		refresh();
	}

	/**
	 * Applies the palette with the given id and refreshes every token.
	 * Unknown ids keep the current palette, so a corrupted config can never
	 * leave the GUI unstyled.
	 */
	public static void setPalette(String paletteId) {
		Palette palette = PALETTES.get(paletteId);
		if (palette != null) {
			active = palette;
			refresh();
		}
	}
}
