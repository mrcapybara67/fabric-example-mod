package com.capy.client.gltf;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws a loaded knife mesh through the frame's geometry collector.
 *
 * <p>There is no vertex buffer and no model class here on purpose: an item in
 * 1.21.11 is drawn by submitting geometry into a node collector, which batches
 * it with everything else in the same render layer and draws it once. So a mesh
 * is re-emitted from its arrays every time it is drawn. For a knife that is a
 * few hundred triangles, which costs less than resolving a model and a
 * transformation per frame would, and it keeps the whole path allocation free
 * apart from the lambda the collector stores.
 *
 * <p>One submission per part, because a part is the unit of texturing. The nine
 * models therefore cost four or five submissions each, which is the same count
 * a four-layer vanilla item model costs.
 *
 * <p>The transformation is the caller's: the item pipeline has already applied
 * the display transform for the context being drawn (first person, GUI, ground),
 * and the knife pose has already been applied on top of that in hand space. The
 * renderer only projects, so there is exactly one place that decides where a
 * knife is in the world and one place that decides what it looks like.
 */
public final class GltfRenderer {
	/** Built render layers, keyed by texture. */
	private static final Map<Identifier, RenderType> LAYERS = new ConcurrentHashMap<>();

	/**
	 * What a part is drawn with when its own sheet is not installed.
	 *
	 * <p>A render layer whose texture is missing is drawn with the magenta and
	 * black checker, so one wrong path - or one sheet a pack override removed -
	 * turns the whole knife into that checker. A plain steel texture is always
	 * present and always readable, so the knife keeps its silhouette.
	 */
	private static final Identifier FALLBACK = Identifier.withDefaultNamespace("textures/block/iron_block.png");

	/**
	 * The finish each style is built in: the guard, the collar and the small
	 * hardware are drawn in this colour, over the material's own blade.
	 *
	 * <p>Without it all six knives are the same steel and the same brown wrap,
	 * which is fine on a shelf and useless in the hand: the colour is the fastest
	 * way to tell a karambit from a stiletto at item scale, and it is what makes
	 * each style read as a designed object rather than a shape.
	 */
	private static final Map<String, Integer> FINISH = Map.of(
			"karambit", 0xFFC8203A,   // lacquered red
			"cleaver", 0xFF4A7FB5,    // blued steel
			"dagger", 0xFF7B4FD8,     // amethyst
			"kukri", 0xFFD98A1E,      // brass
			"stiletto", 0xFF2FB8C6,   // cyan anodised
			"katana", 0xFFD8B24A);    // gold

	/**
	 * The colour each style's blade and handle are multiplied by.
	 *
	 * <p>Without this every style is the same steel and the same brown wrap,
	 * which at item scale reads as one knife with six silhouettes - the thing
	 * players reported as "all my knives are white". The tint is deliberately
	 * light: the vertex colour multiplies the sheet, so a saturated value would
	 * paint over the material and a white one would do nothing. These are the
	 * same hues as {@link #FINISH}, lifted to where the grain of the material
	 * still shows through.
	 */
	private static final Map<String, Integer> STYLE_TINT = Map.of(
			"karambit", 0xFFFFC2CE,
			"cleaver", 0xFFBFD6F2,
			"dagger", 0xFFD6C2FF,
			"kukri", 0xFFFFD79A,
			"stiletto", 0xFFB8EEF6,
			"katana", 0xFFFFE29A);

	private GltfRenderer() {
	}

	/**
	 * Submits every part of the mesh, each with the texture its primitive index
	 * selects and, for the hardware, the style's own finish.
	 */
	public static void render(List<GltfMesh> meshes, String style, String material, PoseStack pose,
			SubmitNodeCollector collector, int light, int overlay) {
		for (GltfMesh mesh : meshes) {
			Identifier texture = textureFor(mesh.part, material, style);
			int tint = tintFor(mesh.part, style);
			collector.submitCustomGeometry(pose, layer(texture),
					(poseState, vertices) -> draw(poseState, vertices, mesh, light, overlay, tint));
		}
	}

	/**
	 * The colour a part is multiplied by. White leaves the sheet untouched, so
	 * the blade keeps its material and the handle keeps its leather.
	 */
	private static int tintFor(int part, String style) {
		// 2 guard and 3+ (collars, pins, pommels) are the metal fittings, and
		// those are finished in the style's own colour at full strength.
		boolean hardware = part == 2
				|| (part >= GltfLoader.PART_ACCENT && part != GltfLoader.PART_EDGE && part != 5);
		if (hardware) {
			Integer finish = style == null ? null : FINISH.get(style);
			if (finish != null) {
				return finish;
			}
		}
		// Blade, grip, honed edge and cord wrap keep their material texture, but
		// carry the style's hue so six knives are six objects in the hand.
		Integer tint = style == null ? null : STYLE_TINT.get(style);
		return tint != null ? tint : 0xFFFFFFFF;
	}

	/** The sheet a primitive draws with. */
	private static Identifier textureFor(int part, String material, String style) {
		return switch (part) {
			case 1 -> KnifeTextures.grip(style);
			case 2 -> KnifeTextures.guard();
			case GltfLoader.PART_EDGE -> KnifeTextures.edge(material);
			case 5 -> KnifeTextures.wrap();
			case 0 -> KnifeTextures.blade(material);
			// Everything past the edge slot is small hardware: collars, pins,
			// pommels. Accent is the sheet painted for exactly that.
			default -> KnifeTextures.accent();
		};
	}

	/**
	 * The render layer for a texture, created once.
	 *
	 * <p>Cutout rather than solid, so a model may use a transparent texel if it
	 * wants to; no cull, because a hand authored blade is thin enough that a
	 * single reversed normal would otherwise leave a hole in it.
	 */
	private static RenderType layer(Identifier texture) {
		// computeIfAbsent would build a layer inside the map lock; the lookup
		// plus put pattern is the same thing without that.
		RenderType cached = LAYERS.get(texture);
		if (cached != null) {
			return cached;
		}
		RenderType created = RenderTypes.entityCutoutNoCull(installed(texture) ? texture : FALLBACK);
		RenderType raced = LAYERS.putIfAbsent(texture, created);
		return raced != null ? raced : created;
	}

  	/**
	 * True when the texture is actually in the loaded packs.
	 *
	 * <p>Only asked once per texture, because the answer is cached with the
	 * layer. A resource manager that is not ready answers "present": the
	 * alternative is a first frame that quietly switches every knife to steel.
	 */
	private static boolean installed(Identifier texture) {
		try {
			Minecraft mc = Minecraft.getInstance();
			var manager = mc == null ? null : mc.getResourceManager();
			if (manager == null) {
				return true;
			}
			// The path already starts with "textures/": KnifeTextures builds a
			// texture location, not a model sprite name. Prepending the folder
			// again asked for "textures/textures/item/knife/...", which never
			// exists, so every knife silently fell back to the iron block - the
			// magenta-and-black checker and the plain grey blade players saw.
			Identifier file = Identifier.fromNamespaceAndPath(texture.getNamespace(),
					texture.getPath() + ".png");
			return manager.getResource(file).isPresent();
		} catch (Throwable ignored) {
			return true;
		}
	}

	private static void draw(PoseStack.Pose pose, VertexConsumer vertices, GltfMesh mesh,
			int light, int overlay, int tint) {
		float[] positions = mesh.positions;
		float[] normals = mesh.normals;
		float[] uvs = mesh.uvs;
		int[] indices = mesh.indices;
		int vertexCount = mesh.vertexCount();
		int triangles = mesh.triangleCount();
		int lightU = light & 0xFFFF;
		int lightV = (light >>> 16) & 0xFFFF;

		for (int triangle = 0; triangle < triangles; triangle++) {
			int i0 = indices != null ? indices[triangle * 3] : triangle * 3;
			int i1 = indices != null ? indices[triangle * 3 + 1] : triangle * 3 + 1;
			int i2 = indices != null ? indices[triangle * 3 + 2] : triangle * 3 + 2;
			if (i0 < 0 || i1 < 0 || i2 < 0
					|| i0 >= vertexCount || i1 >= vertexCount || i2 >= vertexCount) {
				// One bad index in a hand edited file must cost a triangle, not
				// the frame. Missing a triangle is visible; a crash is not
				// fixable by the player.
				continue;
			}
			emit(vertices, pose, positions, normals, uvs, i0, lightU, lightV, overlay, tint);
			emit(vertices, pose, positions, normals, uvs, i1, lightU, lightV, overlay, tint);
			emit(vertices, pose, positions, normals, uvs, i2, lightU, lightV, overlay, tint);
		}
	}

	private static void emit(VertexConsumer vertices, PoseStack.Pose pose, float[] positions,
			float[] normals, float[] uvs, int index, int lightU, int lightV, int overlay,
			int tint) {
		float x = positions[index * 3];
		float y = positions[index * 3 + 1];
		float z = positions[index * 3 + 2];
		float u = uvs != null && uvs.length > index * 2 + 1 ? uvs[index * 2] : 0.0f;
		float v = uvs != null && uvs.length > index * 2 + 1 ? uvs[index * 2 + 1] : 0.0f;
		float nx = normals != null && normals.length > index * 3 + 2 ? normals[index * 3] : 0.0f;
		float ny = normals != null && normals.length > index * 3 + 2 ? normals[index * 3 + 1] : 1.0f;
		float nz = normals != null && normals.length > index * 3 + 2 ? normals[index * 3 + 2] : 0.0f;

		vertices.addVertex(pose, x, y, z);
		// Item rendering in 1.21.11 tints by vertex colour, not by a separate
		// light pass, so white is "the sheet's own colours".
		vertices.setColor((tint >>> 16) & 0xFF, (tint >>> 8) & 0xFF, tint & 0xFF,
				(tint >>> 24) & 0xFF);
		vertices.setUv(u, v);
		vertices.setUv2(lightU, lightV);
		vertices.setOverlay(overlay);
		vertices.setNormal(pose, nx, ny, nz);
	}
}
