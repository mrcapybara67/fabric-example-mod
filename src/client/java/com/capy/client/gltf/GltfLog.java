package com.capy.client.gltf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The few messages the glTF path emits.
 *
 * <p>Separate from the loader so the cache, the renderer and the reload listener
 * all log under one name, and so the same unusual event - a style with no file -
 * is reported once rather than once per caller. Silence is the normal state: the
 * nine models ship with the client.
 */
final class GltfLog {
	private static final Logger LOGGER = LoggerFactory.getLogger("capy/gltf");

	private GltfLog() {
	}

	/**
	 * Styles already reported, so a repeated failure is said once.
	 *
	 * <p>The cache deliberately does not remember a failure: a transient read
	 * during a resource reload must not cost a style until the module is
	 * rebound. The consequence is that a style with no file is asked for on
	 * every frame the item is visible - which, without this set, was a warning
	 * log line per frame, per knife in the inventory. The retry still happens;
	 * only the message is deduplicated.</p>
	 */
	private static final java.util.Set<String> REPORTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

	static void missing(String style) {
		if (REPORTED.add("missing:" + style)) {
			LOGGER.warn("No GLTF model for knife style '{}'; using the cuboid model", style);
		}
	}

	static void loaded(String style, java.util.List<GltfMesh> meshes) {
		int triangles = 0;
		for (GltfMesh mesh : meshes) {
			triangles += mesh.triangleCount();
		}
		LOGGER.info("Loaded GLTF knife '{}': {} parts, {} triangles", style, meshes.size(), triangles);
	}

	static void failed(String style, Throwable failure) {
		if (REPORTED.add("failed:" + style)) {
			LOGGER.warn("Could not read the GLTF model for knife style '{}'", style, failure);
		}
	}

	static void reloadFailure(Throwable failure) {
		LOGGER.warn("GLTF reload failed; knives keep the models they had", failure);
	}
}
