package com.capy.client.gltf;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The loaded knife meshes, one entry per style.
 *
 * <p>Parsing a GLB is a few thousand small reads, which is nothing once but
 * indefensible per frame - the item model resolver touches every visible item on
 * every frame, including the whole inventory. So a style is parsed on first use
 * and kept until the resources reload, and the render path is a plain map
 * lookup.
 *
 * <p>Two caches, not one: a style that failed to load is remembered as failed so
 * a missing file is not re-read sixty times a second, and a reload clears both
 * so adding the file back needs no restart. Failures are per style, so one bad
 * GLB costs that style alone.
 *
 * <p>Access is from the render thread and from the preload thread, so both maps
 * are concurrent and a loaded list is immutable once published.
 */
public final class GltfCache {
	private static final String NAMESPACE = "capy";
	private static final String GLTF_PATH_PREFIX = "models/item/knife/gltf/";

	private static final Map<String, List<GltfMesh>> CACHE = new ConcurrentHashMap<>();
	private static final Set<String> MISSING = ConcurrentHashMap.newKeySet();

	private GltfCache() {
	}

	/**
	 * The meshes for a style, or an empty list when the style has no model.
	 *
	 * <p>Never throws: this is on the item render path, where an exception is a
	 * missing knife at best and a crash at worst. A file that does not parse is
	 * logged once and treated as absent, which sends the caller back to the
	 * cuboid model.
	 */
	public static List<GltfMesh> get(String style, ResourceManager resourceManager) {
		if (style == null || style.isEmpty()) {
			return List.of();
		}
		List<GltfMesh> cached = CACHE.get(style);
		if (cached != null) {
			return cached;
		}
		if (resourceManager == null || MISSING.contains(style)) {
			return List.of();
		}
		return load(style, resourceManager);
	}

	private static List<GltfMesh> load(String style, ResourceManager resourceManager) {
		Identifier id = Identifier.fromNamespaceAndPath(NAMESPACE, GLTF_PATH_PREFIX + style + ".glb");
		try {
			Optional<Resource> resource = resourceManager.getResource(id);
			if (resource.isEmpty()) {
				// Not shipped, or not this style's turn: remember it so the
				// resolver does not stat the pack for it every frame.
				MISSING.add(style);
				GltfLog.missing(style);
				return List.of();
			}
			byte[] bytes;
			try (InputStream in = resource.get().open()) {
				bytes = in.readAllBytes();
			}
			List<GltfMesh> meshes = List.copyOf(GltfLoader.load(bytes));
			CACHE.put(style, meshes);
			MISSING.remove(style);
			GltfLog.loaded(style, meshes);
			return meshes;
		} catch (Exception failure) {
			MISSING.add(style);
			GltfLog.failed(style, failure);
			return List.of();
		}
	}

	/** True when the style has usable geometry, without forcing a load. */
	public static boolean isLoaded(String style) {
		List<GltfMesh> meshes = style == null ? null : CACHE.get(style);
		return meshes != null && !meshes.isEmpty();
	}

	/** Drops everything. Called on a resource reload and when the module is off. */
	public static void invalidate() {
		CACHE.clear();
		MISSING.clear();
	}

	/**
	 * Loads every style ahead of time.
	 *
	 * <p>Run on a background thread when the module is switched on: the first
	 * sword swing after enabling should not pay for a parse, and nine small
	 * files are cheap enough to read at once. A parse failure is logged and
	 * skipped rather than aborting the preload - the other eight styles are
	 * still worth having.
	 */
	public static void preloadAll(ResourceManager resourceManager) {
		if (resourceManager == null) {
			return;
		}
		for (String style : STYLES) {
			get(style, resourceManager);
		}
	}

	/** Mirrors the style list in the module; kept here so preload needs no module. */
	private static final String[] STYLES = {
			"butterfly", "karambit", "cleaver", "dagger", "tanto", "kukri", "machete", "stiletto", "katana"
	};
}
