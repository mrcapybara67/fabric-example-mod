package com.capy.client.gltf;

import com.capy.client.module.modules.CustomKnifeModule;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * <p>Only successful parses are kept, and a resource reload clears the cache so
 * a replaced file needs no restart. Failures are per style, so one bad GLB
 * costs that style alone.
 *
 * <p>Access is from the render thread and from the preload thread, so the map is
 * concurrent and a loaded list is immutable once published.
 */
public final class GltfCache {
	private static final String NAMESPACE = "capy";
	private static final String GLTF_PATH_PREFIX = "models/item/knife/gltf/";

	private static final Map<String, List<GltfMesh>> CACHE = new ConcurrentHashMap<>();

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
		if (resourceManager == null) {
			return List.of();
		}
		// A failure is not remembered. Caching "this style has no mesh" would
		// turn a single transient read - the preload thread running while a
		// resource reload swaps the pack out from under it, say - into a style
		// that stays a plain vanilla sword until the module is toggled, which is
		// indistinguishable from the feature being broken. Retrying costs one
		// map lookup in the resource manager per frame, and only while the style
		// is actually held.
		return load(style, resourceManager);
	}

	private static List<GltfMesh> load(String style, ResourceManager resourceManager) {
		Identifier id = Identifier.fromNamespaceAndPath(NAMESPACE, GLTF_PATH_PREFIX + style + ".glb");
		try {
			Optional<Resource> resource = resourceManager.getResource(id);
			if (resource.isEmpty()) {
				GltfLog.missing(style);
				return List.of();
			}
			byte[] bytes;
			try (InputStream in = resource.get().open()) {
				bytes = in.readAllBytes();
			}
			List<GltfMesh> meshes = List.copyOf(GltfLoader.load(bytes));
			CACHE.put(style, meshes);
			GltfLog.loaded(style, meshes);
			return meshes;
		} catch (Exception failure) {
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
		// The module owns the roster. A style removed from there must not be
		// preloaded here, and a second list is how the two drift apart.
		for (String style : CustomKnifeModule.STYLES) {
			get(style, resourceManager);
		}
	}
}
