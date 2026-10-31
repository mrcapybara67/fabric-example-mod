package com.capy.client.gltf;

import com.capy.client.module.ModuleManager;
import com.capy.client.module.modules.CustomKnifeModule;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.List;
import java.util.function.Consumer;

/**
 * The bridge between an item render state and a glTF mesh.
 *
 * <p>The item pipeline in 1.21.11 draws an item in two steps: build a render
 * state from the stack, then submit the state with a pose that already has the
 * display transform for the current context applied. A "special model" layer is
 * the supported way for a state to carry geometry that is not a baked quad list
 * - it is how a chest, a banner or a shield draw their own geometry - so a knife
 * is one layer holding one of these.
 *
 * <p>This buys the whole placement problem for free, and correctly: the same
 * transform table the shipped item models use (see
 * {@code assets/capy/models/item/knife/*.json}) is applied by vanilla, in every
 * context, including the left hand mirroring. That is why the numbers below are
 * copies of the display blocks in those files rather than new ones - a knife in
 * the GUI and a knife in the hand have to agree with the models they replaced.
 *
 * <p>The knife pose, the swing and the flourish are unaffected: they are pushed
 * in hand space before the state is submitted, so they compose with this
 * transform instead of being replaced by it.
 */
public final class KnifeSpecialRenderer implements SpecialModelRenderer<KnifeSpecialRenderer.Knife> {
	public static final KnifeSpecialRenderer INSTANCE = new KnifeSpecialRenderer();

	/** Everything needed to draw one held knife. */
	public record Knife(String style, String material, List<GltfMesh> meshes) {
	}

	private KnifeSpecialRenderer() {
	}

	/** Attaches a drawn knife to a render state. False when it cannot be drawn. */
	public static boolean attach(ItemStackRenderState state, ItemDisplayContext context,
			String style, String material, List<GltfMesh> meshes) {
		if (state == null || meshes == null || meshes.isEmpty()) {
			return false;
		}
		ItemStackRenderState.LayerRenderState layer = state.newLayer();
		// The layer's submit applies the transform before calling us, so a null
		// one would be a crash rather than a default.
		layer.setTransform(transformFor(context, style));
		layer.setUsesBlockLight(false);
		layer.setupSpecialModel(INSTANCE, new Knife(style, material, meshes));
		return true;
	}

	@Override
	public void submit(Knife knife, ItemDisplayContext context, PoseStack pose,
			SubmitNodeCollector collector, int light, int overlay, boolean foil, int outline) {
		if (knife == null || knife.meshes() == null || knife.meshes().isEmpty()) {
			return;
		}
		// The mesh is authored in item space - one unit per sixteenth of a
		// block - because that is the space a vanilla item model's cuboids are
		// written in, and it is the space the display transform in this class is
		// copied from. A special model, though, is submitted in block space: the
		// transform above is applied around geometry a chest or a shield is
		// authored at, 0..1. Without this division a knife is drawn sixteen
		// times life size - a wall of blade in first person and an item far too
		// large to fit its hotbar slot, which is exactly how it looked.
		pose.pushPose();
		pose.scale(1.0f / 16.0f, 1.0f / 16.0f, 1.0f / 16.0f);
		GltfRenderer.render(knife.meshes(), knife.style(), knife.material(), pose, collector, light, overlay);
		pose.popPose();
	}

	/**
	 * The volume the item occupies, for extents driven layout such as the
	 * inventory's oversized item scaling.
	 *
	 * <p>Reported in blocks, like the geometry - a knife is submitted in the
	 * space a chest occupies, so its box is one block, not sixteen. Reporting
	 * sixteen blocks here made the inventory believe the item was sixteen times
	 * the slot and scale it away.
	 */
	@Override
	public void getExtents(Consumer<Vector3fc> out) {
		for (int x = 0; x <= 1; x++) {
			for (int y = 0; y <= 1; y++) {
				for (int z = 0; z <= 1; z++) {
					out.accept(new Vector3f(x, y, z));
				}
			}
		}
	}

	/**
	 * Resolves a knife from a stack.
	 *
	 * <p>Only used if something other than {@link #attach} builds the layer; the
	 * module passes the resolved parts directly, so this is the path that keeps
	 * the interface honest without paying for a lookup twice.
	 */
	@Override
	public Knife extractArgument(ItemStack stack) {
		CustomKnifeModule module = ModuleManager.get().get(CustomKnifeModule.class);
		if (module == null || stack == null || stack.isEmpty()) {
			return null;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc == null) {
			return null;
		}
		String style = module.styleId();
		List<GltfMesh> meshes = GltfCache.get(style, mc.getResourceManager());
		return meshes.isEmpty() ? null : new Knife(style, CustomKnifeModule.materialOf(stack), meshes);
	}

	/**
	 * The display transform for a context: the same numbers the cuboid knife
	 * models carry in their {@code display} blocks.
	 *
	 * <p>Kept in one switch rather than a map so an unknown context gets the
	 * identity instead of a null.
	 */
	private static ItemTransform transformFor(ItemDisplayContext context, String style) {
		float size = sizeOf(style);
		return switch (context) {
			case FIRST_PERSON_RIGHT_HAND ->
					transform(0.0f, -90.0f, 25.0f, 1.13f, 3.2f, 1.13f, 0.68f * size);
			case FIRST_PERSON_LEFT_HAND ->
					transform(0.0f, 90.0f, -25.0f, 1.13f, 3.2f, 1.13f, 0.68f * size);
			case THIRD_PERSON_RIGHT_HAND ->
					transform(0.0f, -90.0f, 55.0f, 0.0f, 4.0f, 0.5f, 0.85f * size);
			case THIRD_PERSON_LEFT_HAND ->
					transform(0.0f, 90.0f, -55.0f, 0.0f, 4.0f, 0.5f, 0.85f * size);
			case GROUND -> transform(0.0f, 0.0f, 0.0f, 0.0f, 2.0f, 0.0f, 0.5f * size);
			case HEAD -> transform(0.0f, 180.0f, 0.0f, 0.0f, 13.0f, 7.0f, size);
			case FIXED -> transform(0.0f, 180.0f, 0.0f, 0.0f, 0.0f, 0.0f, size);
			case GUI -> transform(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, size);
			default -> ItemTransform.NO_TRANSFORM;
		};
	}

	/**
	 * How large each style is drawn, relative to a sword.
	 *
	 * <p>A dagger and a katana are not the same object, and a fixed size for
	 * both made the short knives look like toys and the long one look like a
	 * cleaver. The values follow the blades: a needle and a dagger are small, a
	 * cleaver is a slab, a katana is the one that is longer than the hand.
	 */
	private static float sizeOf(String style) {
		if (style == null) {
			return 1.0f;
		}
		return switch (style) {
			case "karambit" -> 0.82f;
			case "dagger" -> 0.78f;
			case "stiletto" -> 0.74f;
			case "kukri" -> 0.95f;
			case "cleaver" -> 1.02f;
			case "katana" -> 1.18f;
			default -> 1.0f;
		};
	}

	/**
	 * One display transform, with the numbers written in the model file's own
	 * units.
	 *
	 * <p>The translation is divided by sixteen here, and that is not a fudge:
	 * {@code ItemTransform}'s JSON reader multiplies every translation by
	 * {@code 0.0625} before storing it, because a model's display block is
	 * written in item units (one unit = a sixteenth of a block) while the record
	 * stores blocks. A transform built in code skips that reader, so handing it
	 * the file's numbers put the knife three point two <em>blocks</em> in front
	 * of the camera instead of three point two sixteenths - the blade sat out in
	 * the distance, which is what "too far from my hand" was.
	 */
	private static ItemTransform transform(float rotX, float rotY, float rotZ,
			float tx, float ty, float tz, float scale) {
		return new ItemTransform(
				new Vector3f(rotX, rotY, rotZ),
				new Vector3f(tx / 16.0f, ty / 16.0f, tz / 16.0f),
				new Vector3f(scale, scale, scale));
	}
}
