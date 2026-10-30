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

	static void missing(String style) {
		LOGGER.warn("No GLTF model for knife style '{}'; using the cuboid model", style);
	}

	static void loaded(String style, java.util.List<GltfMesh> meshes) {
		int triangles = 0;
		for (GltfMesh mesh : meshes) {
			triangles += mesh.triangleCount();
		}
		LOGGER.info("Loaded GLTF knife '{}': {} parts, {} triangles", style, meshes.size(), triangles);
	}

	static void failed(String style, Throwable failure) {
		LOGGER.warn("Could not read the GLTF model for knife style '{}'", style, failure);
	}

	static void reloadFailure(Throwable failure) {
		LOGGER.warn("GLTF reload failed; knives keep the models they had", failure);
	}
}
