package com.capy.client.module.modules;

import com.capy.client.config.ConfigValues;
import com.capy.client.gltf.GltfCache;
import com.capy.client.gltf.GltfMesh;
import com.capy.client.gltf.KnifeSpecialRenderer;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.Category;
import com.capy.client.module.ChoiceSetting;
import com.capy.client.module.Configurable;
import com.capy.client.module.Module;
import com.capy.client.module.SliderSetting;
import com.capy.client.module.modules.knife.KnifePose;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Custom Knife - draws the local player's swords as a knife.
 *
 * How it works, and why this way: in 1.21.4+ the appearance of an item is
 * selected by the {@code minecraft:item_model} component, which names an item
 * model definition under {@code assets/<namespace>/items/}. Replacing that
 * component on a <em>copy</em> of the stack, at the moment the render state is
 * built, changes nothing about the real item: the stack in the inventory, its
 * damage, its name, its count and every packet the server sees stay exactly as
 * they were. The alternative - swapping the held item for another item - would
 * have to be undone every tick and would be visible to the server.
 *
 * The knife itself is a real 3D mesh: a glTF file per style, drawn by
 * {@code KnifeSpecialRenderer} with the display transform a vanilla handheld
 * item uses, so it sits in the hand at the sword's angle and scale. One mesh per
 * style and a texture sheet per material means a wooden sword keeps a wooden
 * blade and a netherite one keeps looking like netherite.
 *
 * Holding the inspect key (C by default, rebindable in Controls) plays a short
 * flourish in first person; each style has its own. See {@link KnifePose}.
 *
 * The same class also owns how each style sits in the hand and how it swings:
 * a karambit rests in a reverse grip below the fist, a cleaver chops, a
 * stiletto thrusts. That work is done in hand space by a mixin into the first
 * person item renderer, so it stays pure presentation.
 *
 * Scope: only the local player's own items are changed. {@code owner} is the
 * entity the item is being rendered for, so another player's sword is left
 * alone, and so is an item lying on the ground or in an item frame (those are
 * rendered for the entity that owns them, not for the player).
 */
public class CustomKnifeModule extends Module implements Configurable {
	/**
	 * The knife styles that ship with the client, in menu order.
	 *
	 * Six, not nine. Each remaining style has a silhouette nothing else covers:
	 * the karambit's ring and hook, the cleaver's square slab, the dagger's
	 * double edge, the kukri's forward belly, the stiletto's needle and the
	 * katana's long sori. The balisong's twin bars never read at item scale, and
	 * the tanto and the machete were plain rectangles the dagger and the cleaver
	 * already answer, so those three are gone rather than shipped as filler.
	 *
	 * The first entry is the default for a fresh install, and for a stored config
	 * that names a style that no longer exists.
	 */
	public static final String[] STYLES = {
			"karambit", "cleaver", "dagger", "kukri", "stiletto", "katana"
	};

	/** Sword materials, in the order of {@link #MODELS}' second dimension. */
	private static final String[] MATERIALS = {"wooden", "stone", "iron", "golden", "diamond", "netherite"};

	private final ChoiceSetting style = setting(new ChoiceSetting("style", 0, STYLES));
	private final BooleanSetting inspect = setting(new BooleanSetting("inspect", true));
	/**
	 * Size of the mesh, as a percentage of the authored one.
	 *
	 * <p>A hand authored mesh cannot fit every hand, every GUI scale and every
	 * field of view at once, and the visible size of a knife in first person is
	 * the one thing players disagree about most. One slider answers it without a
	 * rebuild, and it is applied where the mesh is converted into block space, so
	 * it scales the model and not its display transform - the pose still sits it
	 * in the fist.
	 */
	private final SliderSetting scale = setting(new SliderSetting("scale", 100, 50, 200, 0));

	public CustomKnifeModule() {
		super(Category.VISUAL, "custom_knife");
	}

	public ChoiceSetting styleSetting() {
		return style;
	}

	public boolean inspectEnabled() {
		return inspect.value();
	}

	/**
	 * True when the given stack is one this module draws as a knife.
	 *
	 * Deliberately independent of the "inspect animation" setting: that setting
	 * only governs the on demand flourish. The stance and the swing belong to
	 * the knife itself, so turning the flourish off must not turn the karambit
	 * back into a sword held blade up.
	 */
	public boolean armed(ItemStack stack) {
		return isEnabled() && stack != null && !stack.isEmpty() && stack.is(ItemTags.SWORDS);
	}

	/** True when the inspect flourish may be played for the given stack. */
	public boolean inspectReady(ItemStack stack) {
		return armed(stack) && inspect.value();
	}

	/**
	 * Attaches the style's glTF mesh to a render state, so the knife is drawn as
	 * a smooth mesh instead of a cuboid item model.
	 *
	 * <p>Called from the item model resolver, for every visible item. Returns
	 * false, having changed nothing, when there is nothing better to draw: not a
	 * sword, not the local player's item, or no mesh for the selected style. In
	 * that last case {@link #swapFor} still swaps in the cuboid model, so the
	 * client keeps working with the meshes missing, half installed or simply not
	 * yet parsed.
	 */
	public boolean attachGltf(ItemStackRenderState state, ItemStack stack,
			ItemDisplayContext context, ItemOwner owner) {
		if (!isEnabled() || stack == null || stack.isEmpty() || !stack.is(ItemTags.SWORDS)) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || (owner != null && owner != mc.player)) {
			return false;
		}
		// The mesh does not depend on the material: one GLB per style, and the
		// material only selects which blade sheet the mesh is drawn with. That
		// is why there are nine files and not fifty-four.
		String style = styleId();
		List<GltfMesh> meshes = GltfCache.get(style, mc.getResourceManager());
		if (meshes.isEmpty()) {
			return false;
		}
		return KnifeSpecialRenderer.attach(state, context, style, materialOf(stack), meshes,
				scaleValue());
	}

	/**
	 * Enabling reads the meshes ahead of time; disabling frees them.
	 *
	 * <p>The parse happens on the calling thread, on purpose. It used to be
	 * handed to a daemon thread "so switching the module on cannot hitch", and
	 * that thread read the client's resource manager. A resource manager is not
	 * thread safe: it is being read, and its pack list replaced, by the loader
	 * itself while a world is being joined, and a second thread walking it there
	 * is a race with the join - which surfaces as the game crashing the moment a
	 * world finishes loading. Six small files parse in a few milliseconds, and
	 * that is always cheaper than a race with the loader.
	 */
	@Override
	protected void onToggle(boolean state) {
		if (!state) {
			GltfCache.invalidate();
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		ResourceManager manager = mc == null ? null : mc.getResourceManager();
		if (manager == null) {
			// Too early to know where the packs are; the first draw will load
			// the selected style lazily instead.
			return;
		}
		GltfCache.preloadAll(manager);
	}

	/**
	 * Plays the inspect flourish if the player is holding something we draw.
	 *
	 * The check happens here, not in the renderer, so pressing the key with an
	 * empty hand or a pickaxe does nothing at all rather than starting an
	 * animation that the renderer then has to suppress.
	 */
	public void triggerInspect() {
		if (!isEnabled() || !inspect.value()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.player == null || !inspectReady(mc.player.getMainHandItem())) {
			return;
		}
		// The flourish is started for the style the player is holding, which is
		// also the style the renderer will pose. Both read the same setting, so
		// they cannot disagree if it changes mid animation.
		KnifePose.start(styleId());
	}

	/** The selected style id, for the model path and the config file. */
	public String styleId() {
		return style.option();
	}
	/**
	 * Nothing to substitute any more, and deliberately so.
	 *
	 * <p>A knife is the glTF mesh that {@link #attachGltf} attaches to the render
	 * state, not a cuboid item model, so there is no model id to swap in. Doing
	 * nothing is also the correct fallback while a style's mesh is still being
	 * parsed: a model that fails to bake is drawn as vanilla's missing model - a
	 * magenta and black cube filling the item slot - which is exactly the bug a
	 * swap would reintroduce the moment a `.glb` were missing. The vanilla sword
	 * is always a correct thing to draw.
	 */
	public ItemStack swapFor(ItemStack stack, ItemOwner owner) {
		return stack;
	}

	/** The size multiplier the mesh is drawn at, clamped to the slider's range. */
	public float scaleValue() {
		float value = (float) scale.real() / 100f;
		return Math.max(0.5f, Math.min(2.0f, value));
	}

	/** Which sprite palette this sword uses, by name. */
	public static String materialOf(ItemStack stack) {
		return stack == null ? MATERIALS[2] : MATERIALS[materialIndex(stack)];
	}

	/** Which sprite palette this sword uses. Unknown swords fall back to steel. */
	private static int materialIndex(ItemStack stack) {
		if (stack.is(Items.WOODEN_SWORD)) {
			return 0;
		}
		if (stack.is(Items.STONE_SWORD)) {
			return 1;
		}
		if (stack.is(Items.IRON_SWORD)) {
			return 2;
		}
		if (stack.is(Items.GOLDEN_SWORD)) {
			return 3;
		}
		if (stack.is(Items.DIAMOND_SWORD)) {
			return 4;
		}
		if (stack.is(Items.NETHERITE_SWORD)) {
			return 5;
		}
		return 2;
	}

	// ---------------------------------------------------------- persistence

	@Override
	public void loadConfig(JsonObject json) {
		// Stored by id, never by index, so adding a style later cannot turn an
		// existing choice into a different knife.
		if (!style.select(ConfigValues.stringValue(json, "style", STYLES[0]))) {
			style.reset();
		}
		inspect.set(ConfigValues.boolValue(json, "inspect", true));
		scale.set(ConfigValues.intValue(json, "scale", 100));
	}

	@Override
	public void saveConfig(JsonObject json) {
		json.addProperty("style", styleId());
		json.addProperty("inspect", inspect.value());
		json.addProperty("scale", scale.get());
	}
}
