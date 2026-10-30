package com.capy.client.gltf;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a binary glTF file into a list of {@link GltfMesh} triangle lists.
 *
 * <p>Only the narrow slice of glTF that a mesh needs is read: accessors, buffer
 * views and mesh primitives. Nodes, scenes, cameras, animations, skins, sparse
 * accessors and material definitions are ignored - a knife is a single static
 * part whose placement in the hand is decided by the game, not by the file.
 *
 * <h2>Asset spec for the nine knife models</h2>
 *
 * <p>These are the rules the models in {@code assets/capy/models/item/knife/gltf/}
 * follow, and the rules any replacement has to follow to render correctly:
 *
 * <ul>
 *   <li><b>Format</b>: GLTF 2.0, binary ({@code .glb}), one file per style,
 *       named {@code <style>.glb}. The sword material is chosen at runtime from
 *       the held item, so there is no per-material mesh.</li>
 *   <li><b>Space</b>: the same space a vanilla item model uses. Y is up, X is
 *       right, Z points out of the screen, one unit is 1/16 of a block, and the
 *       model lives inside the 0..16 cube. A mesh authored this way needs no
 *       conversion: the item's own display transform - the same numbers a
 *       vanilla {@code item/handheld} model uses - places it in the hand, in the
 *       GUI and on the ground.</li>
 *   <li><b>Orientation</b>: handle at low Y, blade toward high Y, blade turned
 *       -45 degrees about Z so it runs along the diagonal a sword sprite runs
 *       along. That is what the display transform grips.</li>
 *   <li><b>Primitives</b>: triangles only ({@code mode} 4), in material order -
 *       primitive 0 is the blade, 1 the grip, 2 the guard, 3 to 5 accent
 *       hardware, 4 specifically the bright cutting edge. The primitive's index
 *       is the only material information used; material names in the JSON are
 *       ignored, so a model exported without them still renders.</li>
 *   <li><b>Attributes</b>: {@code POSITION} is required. {@code NORMAL} and
 *       {@code TEXCOORD_0} should be present; a primitive without them still
 *       loads, with flat lighting and the texture's corner texel.</li>
 *   <li><b>UVs</b>: in the 0..1 range, mapped to the capy knife textures. The
 *       blade sheet is a gradient across its width - spine on the left, hone on
 *       the right - so a blade face maps U from 0 at the spine to 1 at the edge
 *       and V along the blade.</li>
 *   <li><b>No transforms</b>: node transforms are not applied. Bake the model
 *       into the mesh, which is also what a glTF exporter does by default.</li>
 * </ul>
 *
 * <p>Every failure mode here is reported as an {@link IOException} rather than
 * escaped as a runtime exception: the caller falls back to the cuboid model, so
 * a corrupt or hand-edited file costs the player their knife style, not the
 * frame.
 */
public final class GltfLoader {
	private static final int MODE_TRIANGLES = 4;

	private static final int COMPONENT_BYTE = 5120;
	private static final int COMPONENT_UNSIGNED_BYTE = 5121;
	private static final int COMPONENT_SHORT = 5122;
	private static final int COMPONENT_UNSIGNED_SHORT = 5123;
	private static final int COMPONENT_UNSIGNED_INT = 5125;
	private static final int COMPONENT_FLOAT = 5126;

	/** Primitive index at which "accent hardware" starts. */
	public static final int PART_ACCENT = 3;
	/** Primitive index of the bright cutting edge, when a model has one. */
	public static final int PART_EDGE = 4;

	private GltfLoader() {
	}

	public static List<GltfMesh> load(byte[] glbBytes) throws IOException {
		GltfBuffer buffer = new GltfBuffer(glbBytes);
		JsonObject root;
		try {
			JsonElement parsed = JsonParser.parseString(buffer.getJson());
			if (!parsed.isJsonObject()) {
				throw new IOException("glTF JSON is not an object");
			}
			root = parsed.getAsJsonObject();
		} catch (RuntimeException malformed) {
			throw new IOException("malformed glTF JSON", malformed);
		}

		JsonArray bufferViews = arrayOrEmpty(root, "bufferViews");
		JsonArray accessors = arrayOrEmpty(root, "accessors");
		JsonArray meshes = arrayOrEmpty(root, "meshes");
		ByteBuffer bin = buffer.getBin();

		List<GltfMesh> out = new ArrayList<>();
		for (JsonElement meshElement : meshes) {
			if (!meshElement.isJsonObject()) {
				continue;
			}
			JsonArray primitives = arrayOrEmpty(meshElement.getAsJsonObject(), "primitives");
			for (JsonElement primitiveElement : primitives) {
				if (!primitiveElement.isJsonObject()) {
					continue;
				}
				JsonObject primitive = primitiveElement.getAsJsonObject();
				if (intOr(primitive, "mode", MODE_TRIANGLES) != MODE_TRIANGLES) {
					// Points and lines are legal glTF but not something a knife
					// mesh should contain; drawing them as triangles would be
					// nonsense, so they are skipped.
					continue;
				}
				JsonElement attributesElement = primitive.get("attributes");
				if (attributesElement == null || !attributesElement.isJsonObject()) {
					continue;
				}
				JsonObject attributes = attributesElement.getAsJsonObject();
				if (!attributes.has("POSITION")) {
					continue;
				}
				float[] positions = readFloats(bin, accessors, bufferViews,
						attributes.get("POSITION").getAsInt(), 3, "POSITION");
				if (positions.length < 9) {
					// Fewer than one triangle: nothing to draw.
					continue;
				}
				float[] normals = attributes.has("NORMAL")
						? readFloats(bin, accessors, bufferViews, attributes.get("NORMAL").getAsInt(), 3, "NORMAL")
						: null;
				float[] uvs = attributes.has("TEXCOORD_0")
						? readFloats(bin, accessors, bufferViews, attributes.get("TEXCOORD_0").getAsInt(), 2, "TEXCOORD_0")
						: null;
				int[] indices = primitive.has("indices")
						? readIndices(bin, accessors, bufferViews, primitive.get("indices").getAsInt())
						: null;
				if (indices != null && indices.length < 3) {
					continue;
				}
				out.add(new GltfMesh(positions, normals, uvs, indices, out.size()));
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ buffers

	private static float[] readFloats(ByteBuffer bin, JsonArray accessors, JsonArray bufferViews,
			int accessorIndex, int components, String attribute) throws IOException {
		JsonObject accessor = objectAt(accessors, accessorIndex, "accessor");
		int count = intOr(accessor, "count", 0);
		if (count < 0) {
			throw new IOException("negative " + attribute + " count");
		}
		int componentType = intOr(accessor, "componentType", COMPONENT_FLOAT);
		int size = componentSize(componentType);
		int declared = componentsOf(accessor, attribute);
		if (declared < components) {
			throw new IOException(attribute + " has " + declared + " components, expected " + components);
		}
		float[] out = new float[count * components];
		if (!accessor.has("bufferView")) {
			// A legal way to say "all zeroes"; the mesh is degenerate but valid.
			return out;
		}
		JsonObject view = objectAt(bufferViews, intOr(accessor, "bufferView", -1), "bufferView");
		int elementSize = size * declared;
		int stride = intOr(view, "byteStride", 0);
		if (stride <= 0) {
			stride = elementSize;
		}
		long start = (long) intOr(view, "byteOffset", 0) + intOr(accessor, "byteOffset", 0);
		long end = start + (long) stride * (count - 1) + elementSize;
		if (count > 0 && end > bin.limit()) {
			throw new IOException(attribute + " overruns the binary chunk");
		}
		for (int i = 0; i < count; i++) {
			int base = (int) (start + (long) i * stride);
			for (int c = 0; c < components; c++) {
				out[i * components + c] = readFloat(bin, base + c * size, componentType);
			}
		}
		return out;
	}

	private static int[] readIndices(ByteBuffer bin, JsonArray accessors, JsonArray bufferViews,
			int accessorIndex) throws IOException {
		JsonObject accessor = objectAt(accessors, accessorIndex, "accessor");
		int count = intOr(accessor, "count", 0);
		if (count < 0) {
			throw new IOException("negative index count");
		}
		int componentType = intOr(accessor, "componentType", COMPONENT_UNSIGNED_SHORT);
		int size = componentSize(componentType);
		int[] out = new int[count];
		if (!accessor.has("bufferView")) {
			return out;
		}
		JsonObject view = objectAt(bufferViews, intOr(accessor, "bufferView", -1), "bufferView");
		int stride = intOr(view, "byteStride", 0);
		if (stride <= 0) {
			stride = size;
		}
		long start = (long) intOr(view, "byteOffset", 0) + intOr(accessor, "byteOffset", 0);
		long end = start + (long) stride * (count - 1) + size;
		if (count > 0 && end > bin.limit()) {
			throw new IOException("indices overrun the binary chunk");
		}
		for (int i = 0; i < count; i++) {
			out[i] = (int) readFloat(bin, (int) (start + (long) i * stride), componentType);
		}
		return out;
	}

	/** One component, read little endian at an absolute position. */
	private static float readFloat(ByteBuffer bin, int offset, int componentType) throws IOException {
		if (offset < 0 || offset >= bin.limit()) {
			throw new IOException("accessor reads past the binary chunk");
		}
		return switch (componentType) {
			case COMPONENT_FLOAT -> bin.getFloat(offset);
			case COMPONENT_BYTE -> bin.get(offset);
			case COMPONENT_UNSIGNED_BYTE -> bin.get(offset) & 0xFF;
			case COMPONENT_SHORT -> bin.getShort(offset);
			case COMPONENT_UNSIGNED_SHORT -> bin.getShort(offset) & 0xFFFF;
			case COMPONENT_UNSIGNED_INT -> bin.getInt(offset) & 0xFFFFFFFFL;
			default -> throw new IOException("unknown component type " + componentType);
		};
	}

	private static int componentSize(int componentType) throws IOException {
		return switch (componentType) {
			case COMPONENT_BYTE, COMPONENT_UNSIGNED_BYTE -> 1;
			case COMPONENT_SHORT, COMPONENT_UNSIGNED_SHORT -> 2;
			case COMPONENT_UNSIGNED_INT, COMPONENT_FLOAT -> 4;
			default -> throw new IOException("unknown component type " + componentType);
		};
	}

	/** Component count of a VECn/SCALAR accessor type. */
	private static int componentsOf(JsonObject accessor, String attribute) throws IOException {
		JsonElement type = accessor.get("type");
		String name = type == null || type.isJsonNull() ? "" : type.getAsString();
		return switch (name) {
			case "SCALAR" -> 1;
			case "VEC2" -> 2;
			case "VEC3" -> 3;
			case "VEC4" -> 4;
			default -> throw new IOException(attribute + " has unsupported accessor type '" + name + "'");
		};
	}

	// ------------------------------------------------------------------- json

	private static JsonArray arrayOrEmpty(JsonObject object, String name) {
		JsonElement element = object.get(name);
		return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
	}

	private static JsonObject objectAt(JsonArray array, int index, String what) throws IOException {
		if (index < 0 || index >= array.size()) {
			throw new IOException("missing " + what + " " + index);
		}
		JsonElement element = array.get(index);
		if (element == null || !element.isJsonObject()) {
			throw new IOException("malformed " + what + " " + index);
		}
		return element.getAsJsonObject();
	}

	private static int intOr(JsonObject object, String name, int fallback) {
		JsonElement element = object.get(name);
		if (element == null || element.isJsonNull()) {
			return fallback;
		}
		try {
			return element.getAsInt();
		} catch (RuntimeException notANumber) {
			return fallback;
		}
	}
}
