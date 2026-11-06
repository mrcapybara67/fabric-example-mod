package com.capy.client.gui;

import com.capy.client.config.ClientConfig;

/**
 * The palettes and the weather of the title screen.
 *
 * <p>These are deliberately separate from {@link UiTheme}. That palette styles
 * the client's windows; this one styles the world behind the main menu, where
 * the job is atmosphere rather than legibility of small text. The two share the
 * sakura identity but a menu theme can be much darker or much colder without
 * making a settings panel unreadable.</p>
 *
 * <p>The selected theme is stored in {@code capy-client.json}, so the menu the
 * player picked is the menu they get next launch. Aurora is the default: it is
 * the one that shows off the moving ribbons, and it reads well behind both the
 * vanilla logo and the character.</p>
 */
public enum MenuTheme {
	AURORA("aurora", "Aurora",
			0xFF040A17, 0xFF0A2230, 0xFF0E3B44,
			0xFF0A1A24, 0xFF05101A, 0xFF78E6D0,
			0xFFD8FFF8, 0xFF6FE8C8, 0xFF8BFFE2,
			Effect.AURORA, 0.95f, 0xFFB8FFE8, 0xFF66E0C0),
	GOLDEN_HOUR("golden_hour", "Golden Hour",
			0xFF170D12, 0xFF3B1E1B, 0xFF7A4326,
			0xFF2A1714, 0xFF150C0E, 0xFFFFC98A,
			0xFFFFE6B8, 0xFFFFA45C, 0xFFFFD9A0,
			Effect.MOTES, 0.45f, 0xFFFFE0B0, 0xFFFF9E5E),
	MIDNIGHT("midnight", "Midnight",
			0xFF03040C, 0xFF0A1024, 0xFF142040,
			0xFF081020, 0xFF040812, 0xFF9FB6FF,
			0xFFE8F0FF, 0xFF6C8CFF, 0xFFAFC4FF,
			Effect.STARS, 1.0f, 0xFFDCE8FF, 0xFF8FA8FF),
	OVERCAST("overcast", "Overcast",
			0xFF15171C, 0xFF262B33, 0xFF3A414C,
			0xFF1C2027, 0xFF11141A, 0xFFB8C4D4,
			0xFFD8E0EA, 0xFF8FA0B4, 0xFFBFD0E4,
			Effect.RAIN, 0.15f, 0xFFC8D6E8, 0xFF8FA0B4),
	DEEP_OCEAN("deep_ocean", "Deep Ocean",
			0xFF02080F, 0xFF06202E, 0xFF0A4050,
			0xFF041824, 0xFF020D16, 0xFF7BE8F0,
			0xFFB8FFFF, 0xFF3EC8D8, 0xFF6FF0FF,
			Effect.BUBBLES, 0.35f, 0xFF9FF8FF, 0xFF2AA8C0),
	CRIMSON("crimson", "Crimson",
			0xFF120306, 0xFF2A0A0E, 0xFF4A1214,
			0xFF1A0608, 0xFF0D0305, 0xFFFF7A5A,
			0xFFFFB090, 0xFFE04030, 0xFFFF7A5A,
			Effect.EMBERS, 0.25f, 0xFFFFB070, 0xFFFF3B20),
	SAKURA("sakura", "Sakura",
			0xFF140B12, 0xFF2C1624, 0xFF4A2438,
			0xFF1D1018, 0xFF10080E, 0xFFFFC4D8,
			0xFFFFE3EE, 0xFFFF8FB8, 0xFFFF9EC4,
			Effect.PETALS, 0.55f, 0xFFFFB7D2, 0xFFFFE6F0),
	VOID("void", "Void",
			0xFF020106, 0xFF0B0616, 0xFF1A0A2A,
			0xFF0A0414, 0xFF05020B, 0xFFB28CFF,
			0xFFE0D0FF, 0xFF7A4CFF, 0xFFC49CFF,
			Effect.VOID, 0.85f, 0xFFB48CFF, 0xFF5A2CC0),
	NEON("neon", "Neon",
			0xFF03060C, 0xFF06121E, 0xFF0A2030,
			0xFF04101A, 0xFF02080E, 0xFF00FFE0,
			0xFFB8FFFF, 0xFF00FFD0, 0xFF00FFD0,
			Effect.NEON, 0.65f, 0xFF00FFD0, 0xFFFF2BD6),
	PLUM("plum", "Plum",
			0xFF120C18, 0xFF2A1426, 0xFF4A2038,
			0xFF2B1830, 0xFF1C1022, 0xFFFFB98A,
			0xFFFFE3C2, 0xFFFFC98A, 0xFFFFB7D2,
			Effect.PETALS, 0.8f, 0xFFFFB7D2, 0xFFFFDCE8);

	/** What moves in the scene, beyond the shared gradient and ridges. */
	public enum Effect {
		AURORA,
		MOTES,
		STARS,
		RAIN,
		BUBBLES,
		EMBERS,
		PETALS,
		VOID,
		NEON
	}

	private final String id;
	private final String label;
	private final int skyTop;
	private final int skyMid;
	private final int horizon;
	private final int ridgeFar;
	private final int ridgeNear;
	private final int ridgeLight;
	private final int sun;
	private final int glow;
	private final int accent;
	private final Effect effect;
	private final float starAlpha;
	private final int particleA;
	private final int particleB;

	MenuTheme(String id, String label, int skyTop, int skyMid, int horizon, int ridgeFar,
			int ridgeNear, int ridgeLight, int sun, int glow, int accent, Effect effect,
			float starAlpha, int particleA, int particleB) {
		this.id = id;
		this.label = label;
		this.skyTop = skyTop;
		this.skyMid = skyMid;
		this.horizon = horizon;
		this.ridgeFar = ridgeFar;
		this.ridgeNear = ridgeNear;
		this.ridgeLight = ridgeLight;
		this.sun = sun;
		this.glow = glow;
		this.accent = accent;
		this.effect = effect;
		this.starAlpha = starAlpha;
		this.particleA = particleA;
		this.particleB = particleB;
	}

	public String id() {
		return id;
	}

	public String label() {
		return label;
	}

	public int skyTop() {
		return skyTop;
	}

	public int skyMid() {
		return skyMid;
	}

	public int horizon() {
		return horizon;
	}

	public int ridgeFar() {
		return ridgeFar;
	}

	public int ridgeNear() {
		return ridgeNear;
	}

	public int ridgeLight() {
		return ridgeLight;
	}

	public int sun() {
		return sun;
	}

	public int glow() {
		return glow;
	}

	public int accent() {
		return accent;
	}

	public Effect effect() {
		return effect;
	}

	public float starAlpha() {
		return starAlpha;
	}

	public int particleA() {
		return particleA;
	}

	public int particleB() {
		return particleB;
	}

	/** The theme stored in the client config, or Aurora for an unknown id. */
	public static MenuTheme selected() {
		try {
			String id = ClientConfig.get().menuTheme();
			MenuTheme found = byId(id);
			if (found != null) {
				return found;
			}
		} catch (RuntimeException ignored) {
			// A config that is not ready yet is not a reason to fail drawing.
		}
		return AURORA;
	}

	/** The next theme in display order, wrapping at the end. */
	public static MenuTheme next() {
		MenuTheme[] all = values();
		MenuTheme current = selected();
		return all[(current.ordinal() + 1) % all.length];
	}

	/** Looks a theme up by its persisted id, or null. */
	public static MenuTheme byId(String id) {
		if (id == null) {
			return null;
		}
		for (MenuTheme theme : values()) {
			if (theme.id.equalsIgnoreCase(id)) {
				return theme;
			}
		}
		return null;
	}
}
