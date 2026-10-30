package com.capy.client.gltf;

/**
 * One triangle list from a glTF file, ready to be handed to a vertex consumer.
 *
 * <p>The arrays are flat and parallel: vertex {@code i} is at
 * {@code positions[i * 3]}..{@code +2}, and so on. That is the same layout the
 * accessors are read out of, so nothing is copied or repacked between parsing
 * and drawing.
 *
 * <p>{@link #normals} and {@link #uvs} are allowed to be null — a primitive is
 * not required to carry them — and the renderer substitutes a usable value
 * rather than dropping the geometry. {@link #indices} is null for a primitive
 * stored as a plain triangle soup, which is also legal.
 *
 * <p>{@link #part} is the primitive's position in the file, and it is what
 * selects the texture: in these models primitive 0 is the blade, 1 the grip,
 * 2 the guard and anything after that is accent hardware. That mapping is the
 * only "material" information a GLB exported without material names carries,
 * so it is fixed by the asset spec rather than guessed at load time.
 */
public final class GltfMesh {
	public final float[] positions;
	public final float[] normals;
	public final float[] uvs;
	public final int[] indices;
	/** Primitive index within the file: 0 blade, 1 grip, 2 guard, 3+ accent. */
	public final int part;

	public GltfMesh(float[] positions, float[] normals, float[] uvs, int[] indices, int part) {
		this.positions = positions;
		this.normals = normals;
		this.uvs = uvs;
		this.indices = indices;
		this.part = part;
	}

	/** Number of vertices, not of floats. */
	public int vertexCount() {
		return positions == null ? 0 : positions.length / 3;
	}

	/** Number of complete triangles this primitive will draw. */
	public int triangleCount() {
		int count = indices != null ? indices.length : vertexCount();
		return count / 3;
	}
}
