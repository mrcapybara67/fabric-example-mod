package com.capy.client.gui;

import com.capy.client.util.Guard;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Code-generated UI textures. A white rounded mask is uploaded once per kind
 * and radius, then nine-sliced with a vertex tint. Nothing here is loaded from
 * another client and no shader is used.
 *
 * <p>The mask generation is pure enough to test without a game context. All
 * colours are tinted at draw time, so one texture serves every surface and
 * every accent.</p>
 */
public final class UiSprites {
	public enum Kind {
		PANEL, CARD, CARD_HOVER, BUTTON, TOGGLE_TRACK, TOGGLE_KNOB,
		SLIDER_TRACK, SLIDER_FILL, INPUT, SHADOW, BORDER, SHEEN, NOISE, VIGNETTE
	}

	public static final int SIZE = 64;
	public static final int SRC_CORNER = 20;
	public static final int SRC_CENTER = SIZE - SRC_CORNER * 2;
	private static final int[] RADII = {6, 10, 14, 20};
	private static final Map<Kind, Identifier[]> IDS = new EnumMap<>(Kind.class);
	private static boolean built;
	private static long lastCheck;
	private static boolean failed;

	private UiSprites() {
	}

	public static void ensureBuilt() {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.getTextureManager() == null || failed) {
			return;
		}
		long now = System.nanoTime();
		if (built && now - lastCheck < 1_000_000_000L) {
			return;
		}
		lastCheck = now;
		if (built) {
			Identifier first = IDS.get(Kind.PANEL)[0];
			if (mc.getTextureManager().getTexture(first) != null) {
				return;
			}
			built = false;
		}
		try {
			for (Kind kind : Kind.values()) {
				Identifier[] ids = new Identifier[RADII.length];
				for (int i = 0; i < RADII.length; i++) {
					ids[i] = create(mc, kind, RADII[i]);
				}
				IDS.put(kind, ids);
			}
			built = true;
		} catch (Throwable failure) {
			failed = true;
			built = false;
			Guard.report("ui sprites", failure);
		}
	}

	private static Identifier create(Minecraft mc, Kind kind, int radius) {
		NativeImage image = image(kind, radius);
		Identifier id = Identifier.fromNamespaceAndPath("capy",
				"ui/" + kind.name().toLowerCase(Locale.ROOT) + "_" + radius);
		DynamicTexture texture = new DynamicTexture(() -> "capy-ui-" + kind, image);
		mc.getTextureManager().register(id, texture);
		return id;
	}

	public static Identifier id(Kind kind, int radius) {
		Identifier[] ids = IDS.get(kind);
		if (!built || ids == null) {
			return null;
		}
		return ids[bucket(radius)];
	}

	public static int bucket(int radius) {
		if (radius <= RADII[0]) {
			return 0;
		}
		if (radius <= RADII[1]) {
			return 1;
		}
		if (radius <= RADII[2]) {
			return 2;
		}
		return 3;
	}

	public static NativeImage image(Kind kind, int radius) {
		NativeImage image = new NativeImage(SIZE, SIZE, false);
		int[] alpha = alphaMask(kind, SIZE, radius);
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				int a = alpha[y * SIZE + x];
				image.setPixelABGR(x, y, (a << 24) | 0x00FFFFFF);
			}
		}
		return image;
	}

	/**
	 * White shape coverage. The four sample points per pixel give a smooth edge
	 * at any GUI scale without a shader or a mipmap.
	 */
	public static int[] alphaMask(Kind kind, int size, int radius) {
		int[] alpha = new int[size * size];
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				alpha[y * size + x] = alphaAt(kind, size, radius, x, y);
			}
		}
		return alpha;
	}

	public static int alphaAt(Kind kind, int size, int radius, int px, int py) {
		if (kind == Kind.NOISE) {
			int n = (px * 31 + py * 17 + px * py * 7) & 15;
			return n < 5 ? n + 2 : 0;
		}
		if (kind == Kind.VIGNETTE) {
			float edge = Math.min(Math.min(px, py), Math.min(size - 1 - px, size - 1 - py));
			float t = 1f - Math.min(1f, edge / (size * 0.44f));
			return Math.round(150f * t * t);
		}
		if (kind == Kind.SHEEN) {
			float nx = px / (float) size;
			float ny = py / (float) size;
			float band = (float) Math.exp(-Math.pow((nx + ny * 0.12f - 0.58f) / 0.22f, 2.0));
			return Math.round(70f * band * (0.4f + 0.6f * (1f - ny)));
		}
		if (kind == Kind.SHADOW) {
			int samples = 0;
			for (int sy = 0; sy < 2; sy++) {
				for (int sx = 0; sx < 2; sx++) {
					if (inside(px + 0.25f + sx * 0.5f, py + 0.25f + sy * 0.5f,
							size, size, radius, 0f)) {
						samples++;
					}
				}
			}
			return Math.round(95f * samples / 4f);
		}
		int samples = 0;
		for (int sy = 0; sy < 2; sy++) {
			for (int sx = 0; sx < 2; sx++) {
				float x = px + 0.25f + sx * 0.5f;
				float y = py + 0.25f + sy * 0.5f;
				if (inside(x, y, size, size, radius, 0f)) {
					if (kind == Kind.BORDER && inside(x, y, size, size, radius, 1.2f)) {
						continue;
					}
					samples++;
				}
			}
		}
		return Math.round(255f * samples / 4f);
	}

	private static boolean inside(float x, float y, float w, float h, float radius, float inset) {
		float halfW = w * 0.5f - inset;
		float halfH = h * 0.5f - inset;
		if (halfW <= 0 || halfH <= 0) {
			return false;
		}
		float r = Math.max(0, Math.min(radius - inset, Math.min(halfW, halfH)));
		float dx = Math.abs(x - w * 0.5f) - (halfW - r);
		float dy = Math.abs(y - h * 0.5f) - (halfH - r);
		float outside = (float) Math.sqrt(Math.max(dx, 0) * Math.max(dx, 0)
				+ Math.max(dy, 0) * Math.max(dy, 0));
		return outside + Math.min(Math.max(dx, dy), 0) - r <= 0f;
	}

	/**
	 * Nine-slice dimensions for one axis: target corner, target centre,
	 * source corner, source centre. The values are deliberately pure so the
	 * 9-slice math can be tested without a game context.
	 */
	public static int[] slice(int targetSize, int targetCorner, int sourceSize, int sourceCorner) {
		int corner = Math.max(0, Math.min(targetCorner, targetSize / 2));
		int source = Math.max(0, Math.min(sourceCorner, sourceSize / 2));
		return new int[]{corner, Math.max(0, targetSize - corner * 2),
				source, Math.max(0, sourceSize - source * 2)};
	}

	/** The destination centre size after both corners are removed. */
	public static int centreSize(int targetSize, int targetCorner) {
		int corner = Math.max(0, Math.min(targetCorner, targetSize / 2));
		return Math.max(0, targetSize - corner * 2);
	}

	public static boolean ready() {
		return built;
	}
}
