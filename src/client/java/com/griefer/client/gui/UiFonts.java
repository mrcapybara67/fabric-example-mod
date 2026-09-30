package com.griefer.client.gui;

import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.datafixers.util.Either;
import java.io.IOException;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.GlyphStitcher;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.client.gui.font.providers.GlyphProviderDefinition;
import net.minecraft.client.gui.font.providers.TrueTypeGlyphProviderDefinition;
import net.minecraft.client.gui.font.glyphs.EffectGlyph;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Griefer's UI typography: real vector glyphs from bundled Manrope TTFs
 * (OFL-licensed) rendered through vanilla's own TrueType glyph pipeline.
 *
 * Vector-smooth geometric letterforms, three static weights:
 *   regular()  = Manrope 500 — body text, descriptions, values
 *   medium()   = Manrope 600 — module/category names, labels
 *   bold()     = Manrope 700 — page title, emphasized text
 *
 * Lifecycle: fonts are created lazily on first GUI use, closed when the
 * resource manager changes (F3+T / pack switch — glyph atlases reference
 * resource-backed TTF data), and closed on client shutdown. Nothing ticks
 * or persists while the GUI is closed.
 *
 * Failure mode: if the TTFs cannot be loaded (broken pack, bad download),
 * the GUI transparently falls back to vanilla's font instead of crashing.
 */
public final class UiFonts {
	private static final Logger LOGGER = LoggerFactory.getLogger(UiFonts.class);
	private static final float SIZE = 8.5f;
	private static final float OVERSAMPLE = 2.0f;

	private static FontSet setRegular;
	private static FontSet setMedium;
	private static FontSet setBold;
	private static List<GlyphProvider> providers = List.of();
	private static Font regular;
	private static Font medium;
	private static Font bold;
	private static boolean initializing;
	private static boolean failed;
	private static ResourceManager lastResources;

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

	/** Detects resource reloads cheaply (one identity compare per tick). */
	public static void tick(Minecraft mc) {
		ResourceManager current = mc.getResourceManager();
		if (lastResources != null && lastResources != current && (regular != null || failed)) {
			invalidate();
		}
		lastResources = current;
	}

	private static void ensureCreated() {
		if (regular != null || initializing || failed) {
			return;
		}
		initializing = true;
		try {
			Minecraft mc = Minecraft.getInstance();
			ResourceManager resources = mc.getResourceManager();
			lastResources = resources;

			GlyphProvider p500 = load(provider("manrope-500.ttf"), resources);
			GlyphProvider p600 = load(provider("manrope-600.ttf"), resources);
			GlyphProvider p700 = load(provider("manrope-700.ttf"), resources);
			providers = List.of(p500, p600, p700);

			setRegular = build(mc, "regular", p500);
			setMedium = build(mc, "medium", p600);
			setBold = build(mc, "bold", p700);

			regular = fontFor(setRegular);
			medium = fontFor(setMedium);
			bold = fontFor(setBold);
		} catch (Throwable t) {
			LOGGER.warn("Griefer: could not load UI fonts, falling back to vanilla font", t);
			failed = true;
			Minecraft mc = Minecraft.getInstance();
			regular = mc.font;
			medium = mc.font;
			bold = mc.font;
		} finally {
			initializing = false;
		}
	}

	/** Loads via the public Loader path (the record's own load() is private). */
	private static GlyphProvider load(TrueTypeGlyphProviderDefinition definition, ResourceManager resources) {
		try {
			Either<GlyphProviderDefinition.Loader, ?> unpacked = definition.unpack();
			GlyphProviderDefinition.Loader loader = unpacked.left()
					.orElseThrow(() -> new IllegalStateException("TrueType definition did not provide a loader"));
			return loader.load(resources);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to load " + definition.location(), e);
		}
	}

	private static TrueTypeGlyphProviderDefinition provider(String file) {
		return new TrueTypeGlyphProviderDefinition(
				Identifier.fromNamespaceAndPath("griefer", "font/" + file),
				SIZE,
				OVERSAMPLE,
				TrueTypeGlyphProviderDefinition.Shift.NONE,
				""
		);
	}

	private static FontSet build(Minecraft mc, String name, GlyphProvider provider) {
		Identifier id = Identifier.fromNamespaceAndPath("griefer", "ui_" + name);
		FontSet set = new FontSet(new GlyphStitcher(mc.getTextureManager(), id));
		set.reload(
				List.of(new GlyphProvider.Conditional(provider, FontOption.Filter.ALWAYS_PASS)),
				java.util.Set.of()
		);
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

	/** Closes all glyph atlases and providers; fonts rebuild on next use. */
	public static void invalidate() {
		regular = null;
		medium = null;
		bold = null;
		closeQuietly(setRegular);
		closeQuietly(setMedium);
		closeQuietly(setBold);
		setRegular = null;
		setMedium = null;
		setBold = null;
		for (GlyphProvider p : providers) {
			try {
				p.close();
			} catch (Exception ignored) {
			}
		}
		providers = List.of();
	}

	private static void closeQuietly(FontSet set) {
		if (set != null) {
			try {
				set.close();
			} catch (Exception ignored) {
			}
		}
	}
}
