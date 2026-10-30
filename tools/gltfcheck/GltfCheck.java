import com.capy.client.gltf.GltfLoader;
import com.capy.client.gltf.GltfMesh;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Offline check for the real glTF loader: parses every shipped mesh and asserts
 * the invariants the renderer relies on.
 */
public final class GltfCheck {
	private static final String[] STYLES = {
			"karambit", "cleaver", "dagger", "kukri", "stiletto", "katana"
	};

	public static void main(String[] args) throws Exception {
		Path directory = Path.of(args[0]);
		int failures = 0;
		for (String style : STYLES) {
			Path file = directory.resolve(style + ".glb");
			byte[] bytes = Files.readAllBytes(file);
			List<GltfMesh> meshes = GltfLoader.load(bytes);
			try {
				failures += check(style, meshes);
			} catch (AssertionError failure) {
				System.out.println("FAIL " + style + ": " + failure.getMessage());
				failures++;
			}
		}
		if (failures > 0) {
			System.out.println(failures + " failure(s)");
			System.exit(1);
		}
		System.out.println("all meshes valid");
	}

	private static int check(String style, List<GltfMesh> meshes) {
		require(!meshes.isEmpty(), "no meshes");
		int triangles = 0;
		for (GltfMesh mesh : meshes) {
			require(mesh.part >= 0 && mesh.part <= 5, "part " + mesh.part);
			require(mesh.positions != null && mesh.positions.length % 3 == 0, "positions");
			require(mesh.normals != null && mesh.normals.length == mesh.positions.length, "normals");
			require(mesh.uvs != null && mesh.uvs.length == mesh.vertexCount() * 2, "uvs");
			require(mesh.indices != null, "indices");
			int vertexCount = mesh.vertexCount();
			for (int index : mesh.indices) {
				require(index >= 0 && index < vertexCount, "index " + index + " of " + vertexCount);
			}
			require(mesh.indices.length % 3 == 0, "index count");
			for (int i = 0; i < mesh.positions.length; i++) {
				float value = mesh.positions[i];
				require(value > -32f && value < 32f, "position " + value);
			}
			for (int i = 0; i < mesh.uvs.length; i++) {
				require(mesh.uvs[i] >= -0.001f && mesh.uvs[i] <= 1.001f, "uv " + mesh.uvs[i]);
			}
			for (int v = 0; v < vertexCount; v++) {
				float nx = mesh.normals[v * 3];
				float ny = mesh.normals[v * 3 + 1];
				float nz = mesh.normals[v * 3 + 2];
				float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
				require(Math.abs(length - 1f) < 0.01f, "normal length " + length);
			}
			triangles += mesh.triangleCount();
		}
		System.out.printf("%-10s parts=%d vertices=%d triangles=%d%n",
				style, meshes.size(), meshes.stream().mapToInt(GltfMesh::vertexCount).sum(), triangles);
		return 0;
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
