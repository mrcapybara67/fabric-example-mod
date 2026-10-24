package com.capy.client.gui;

import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.TrueTypeGlyphProvider;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.GlyphStitcher;
import net.minecraft.client.gui.font.providers.FreeTypeUtil;
import net.minecraft.client.gui.font.glyphs.EffectGlyph;
import net.minecraft.network.chat.FontDescription;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.freetype.FreeType;
import org.lwjgl.util.freetype.FT_Face;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Capy's UI typography: real vector glyphs from bundled Inter TTFs
 * (SIL Open Font License) rendered through the same TrueType pipeline vanilla
 * uses for its own fonts.
 *
 * Vector-smooth grotesque letterforms in five static cuts:
 *   regular()  = Inter 400 — body text, descriptions, values
 *   medium()   = Inter 500 — module names, labels
 *   bold()     = Inter 600 — headings, emphasised values
 *   display()  = Inter 700 at a larger size — wordmark, alert headline
 *   headline() = Inter 700, larger again — the Anti Admin alert
 *
 * Why the fonts are opened directly instead of going through
 * {@code TrueTypeGlyphProviderDefinition}: that definition resolves its
 * identifier as {@code assets/<ns>/font/<path>}, so passing an identifier that
 * already contains a {@code font/} segment looks for {@code font/font/...} and
 * silently fails to load. Owning the FreeType setup here removes that whole
 * class of bug — the bytes come straight from our own jar, and nothing depends
 * on the active resource packs.
 *
 * Consequences of that choice:
 *  - F3+T / resource pack reloads need no bookkeeping: the fonts are ours and
 *    never change, so nothing is rebuilt or closed behind the user's back.
 *
 * Failure mode: if the TTFs are missing or corrupt, the GUI falls back to
 * vanilla's font and logs at ERROR level (it must never fail silently — a
 * silent fallback is indistinguishable from "we shipped the wrong font").
 */
public final class UiFonts {
	private static final Logger LOGGER = LoggerFactory.getLogger(UiFonts.class);

	/** Rendered pixel size. Inter is optically small, so this sits a touch high. */
	private static final float SIZE = 9.0f;
	/** Size of the display weight used for the wordmark and alert headlines. */
	private static final float DISPLAY_SIZE = 12.0f;
	/**
	 * Size of the headline weight. Large on purpose: the Anti Admin alert is
	 * the one piece of text in the client that has to be read instantly, from
	 * the middle of the screen, without stopping to look at it.
	 */
	private static final float HEADLINE_SIZE = 17.0f;
	/**
	 * Glyph supersampling. Three times instead of two is what keeps the small
	 * Inter cuts crisp at 1080p and above, where the GUI scale magnifies each
	 * glyph: at two the stems of 9 px text soften into grey.
	 */
	private static final float OVERSAMPLE = 3.0f;

	/** Bundle-relative path of each static weight. */
	private static final String REGULAR_TTF = "/assets/capy/font/inter-500.ttf";
	private static final String MEDIUM_TTF = "/assets/capy/font/inter-600.ttf";
	private static final String BOLD_TTF = "/assets/capy/font/inter-700.ttf";
	/** The true regular weight, used for body text. */
	private static final String LIGHT_TTF = "/assets/capy/font/inter-400.ttf";

	private static FontSet setRegular;
	private static FontSet setMedium;
	private static FontSet setBold;
	private static FontSet setDisplay;
	private static FontSet setHeadline;
	/** Strong references to the loaded providers: each owns a native FT_Face. */
	private static List<GlyphProvider> providers = List.of();
	private static Font regular;
	private static Font medium;
	private static Font bold;
	private static Font display;
	private static Font headline;
	private static boolean initializing;
	private static boolean failed;
	private static int attempts;

	private UiFonts() {
	}

	public static Font regular() {
		ensureCreated();
		return regular;
	}

	public static Font medium() {
		ensureCreated();
		return medium;
	}

	public static Font bold() {
		ensureCreated();
		return bold;
	}

	/** Large weight for the wordmark and the alert headline. */
	public static Font display() {
		ensureCreated();
		return display;
	}

	/** The largest weight, used only by the Anti Admin alert. */
	public static Font headline() {
		ensureCreated();
		return headline;
	}

	/**
	 * Builds the bundled faces ahead of the first frame that needs them.
	 *
	 * The faces are rasterised into a GPU texture, so creating them in the
	 * middle of a frame - which is what the lazy path did the first time the
	 * GUI or the HUD asked for a font - means uploading a texture while a draw
	 * batch is open. Calling this from the tick moves that work to a moment
	 * where nothing is being drawn. It is idempotent and cheap once loaded.
	 */
	public static void warmUp() {
		ensureCreated();
	}

	/**
	 * Loads the bundled faces.
	 *
	 * The faces are rasterised into a GPU texture, so this can only run once
	 * the client has a render context. A first attempt that happens too early
	 * must not poison the client for the rest of the session, therefore a
	 * failure is retried a few times before the vanilla font is used for good.
	 */
	private static void ensureCreated() {
		if (regular != null || initializing || failed) {
			return;
		}
		if (attempts >= MAX_ATTEMPTS) {
			failed = true;
			return;
		}
		attempts++;
		initializing = true;
		List<GlyphProvider> loaded = new ArrayList<>();
		try {
			Minecraft mc = Minecraft.getInstance();

			loaded.add(loadFromBundle(LIGHT_TTF, SIZE));
			loaded.add(loadFromBundle(REGULAR_TTF, SIZE));
			loaded.add(loadFromBundle(MEDIUM_TTF, SIZE));
			loaded.add(loadFromBundle(BOLD_TTF, SIZE));
			loaded.add(loadFromBundle(BOLD_TTF, DISPLAY_SIZE));
			loaded.add(loadFromBundle(BOLD_TTF, HEADLINE_SIZE));
			providers = List.copyOf(loaded);

			setRegular = buildSet(mc, "regular", loaded.get(0));
			setMedium = buildSet(mc, "medium", loaded.get(1));
			setBold = buildSet(mc, "bold", loaded.get(2));
			// Index 3 is the 9 px bold face; the display and headline cut are the
			// two larger rasterisations that follow it. Keeping the display face
			// on the 9 px entry was why the in-game wordmark was smaller than the
			// preview: the preview always used 12 px.
			setDisplay = buildSet(mc, "display", loaded.get(4));
			setHeadline = buildSet(mc, "headline", loaded.get(5));

			regular = fontFor(setRegular);
			medium = fontFor(setMedium);
			bold = fontFor(setBold);
			display = fontFor(setDisplay);
			headline = fontFor(setHeadline);
		} catch (Throwable t) {
			// Loud on purpose: a silent fallback is impossible to tell apart
			// from shipping the wrong font. The load is retried, because the
			// usual cause is a missing render context during startup.
			if (attempts == 1) {
				LOGGER.error("Capy: could not build the bundled Inter fonts ({} / {} / {} / {}), retrying.",
						LIGHT_TTF, REGULAR_TTF, MEDIUM_TTF, BOLD_TTF, t);
			} else {
				LOGGER.warn("Capy: still no bundled Inter fonts (attempt {}), retrying.", attempts);
			}
			// Give the faces that were already opened back.
			for (GlyphProvider provider : loaded) {
				try {
					provider.close();
				} catch (Throwable ignored) {
					// Nothing else to do, the face is gone either way.
				}
			}
			loaded.clear();
		} finally {
			initializing = false;
		}
	}

	/**
	 * The number of attempts before the client stops trying to build the
	 * bundled faces and uses the vanilla font for good.
	 */
	private static final int MAX_ATTEMPTS = 4;

	/**
	 * Opens one bundled TTF and rasterizes it with FreeType.
	 *
	 * Mirrors the sequence vanilla performs inside
	 * {@code TrueTypeGlyphProviderDefinition.load}: read the whole font into a
	 * direct buffer, open a memory-backed face, select the Unicode charmap, and
	 * hand the face to the provider (which owns and frees it from then on).
	 */
	private static GlyphProvider loadFromBundle(String resource, float size) {
		byte[] bytes = readBundle(resource);
		ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length);
		buffer.put(bytes);
		buffer.flip();

		FT_Face face;
		synchronized (FreeTypeUtil.LIBRARY_LOCK) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				PointerBuffer holder = stack.mallocPointer(1);
				FreeTypeUtil.assertError(
						FreeType.FT_New_Memory_Face(FreeTypeUtil.getLibrary(), buffer, 0L, holder),
						"Initializing font face for " + resource);
				face = FT_Face.create(holder.get());
			} catch (Throwable t) {
				MemoryUtil.memFree(buffer);
				throw new IllegalStateException("Failed to open " + resource, t);
			}
		}

		String format = FreeType.FT_Get_Font_Format(face);
		if (!"TrueType".equals(format)) {
			FreeType.FT_Done_Face(face);
			MemoryUtil.memFree(buffer);
			throw new IllegalStateException(resource + " is not a TrueType font (format: " + format + ")");
		}

		FreeTypeUtil.assertError(
				FreeType.FT_Select_Charmap(face, FreeType.FT_ENCODING_UNICODE),
				"Selecting the Unicode charmap for " + resource);

		// size, oversample, shiftX, shiftY, skip
		return new TrueTypeGlyphProvider(buffer, face, size, OVERSAMPLE, 0.0f, 0.0f, "");
	}

	private static byte[] readBundle(String resource) {
		try (InputStream in = UiFonts.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IllegalStateException("Missing bundled font resource " + resource);
			}
			return in.readAllBytes();
		} catch (IOException e) {
			throw new IllegalStateException("Could not read " + resource, e);
		}
	}

	private static FontSet buildSet(Minecraft mc, String name, GlyphProvider provider) {
		net.minecraft.resources.Identifier id =
				net.minecraft.resources.Identifier.fromNamespaceAndPath("capy", "ui_" + name);
		FontSet set = new FontSet(new GlyphStitcher(mc.getTextureManager(), id));
		set.reload(
				List.of(new GlyphProvider.Conditional(provider, FontOption.Filter.ALWAYS_PASS)),
				Set.of());
		return set;
	}

	private static Font fontFor(FontSet set) {
		return new Font(new Font.Provider() {
			@Override
			public GlyphSource glyphs(FontDescription description) {
				return set.source(false);
			}

			@Override
			public EffectGlyph effect() {
				return set.whiteGlyph();
			}
		});
	}

}
