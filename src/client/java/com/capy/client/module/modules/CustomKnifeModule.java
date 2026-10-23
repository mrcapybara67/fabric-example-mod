package com.capy.client.module.modules;

import com.capy.client.config.ConfigValues;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.Category;
import com.capy.client.module.ChoiceSetting;
import com.capy.client.module.Configurable;
import com.capy.client.module.Module;
import com.capy.client.module.modules.knife.KnifeInspect;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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
 * The models themselves are real 3D geometry: cuboids with thickness, bevelled
 * tips, separate cutting edges and their own hardware, baked with the same
 * display transforms a vanilla handheld item uses so the knife sits in the
 * hand at the sword's angle and scale. One model per style and sword material
 * means a wooden sword keeps a wooden handle and a netherite one keeps looking
 * like netherite.
 *
 * Holding the inspect key (C by default, rebindable in Controls) plays a short
 * flourish in first person; each style has its own. See {@link KnifeInspect}.
 *
 * Scope: only the local player's own items are changed. {@code owner} is the
 * entity the item is being rendered for, so another player's sword is left
 * alone, and so is an item lying on the ground or in an item frame (those are
 * rendered for the entity that owns them, not for the player).
 */
public class CustomKnifeModule extends Module implements Configurable {
	/** The knife styles that ship with the client, in menu order. */
	public static final String[] STYLES = {
			"butterfly", "karambit", "cleaver", "dagger", "tanto", "kukri", "machete", "stiletto"
	};

	/** Sword materials, in the order of {@link #MODELS}' second dimension. */
	private static final String[] MATERIALS = {"wooden", "stone", "iron", "golden", "diamond", "netherite"};

	private static final String NAMESPACE = "capy";
	private static final String MODEL_PREFIX = "knife/";

	/**
	 * A model id that is never shipped. Asking the model manager for it returns
	 * the shared "missing model" instance, which is how a model that did not
	 * load can be recognised without reaching into the bakery.
	 */
	private static final Identifier MISSING_PROBE =
			Identifier.fromNamespaceAndPath(NAMESPACE, "capy_missing_model_probe");

	/** Prebuilt ids, indexed [style][material], so rendering never allocates one. */
	private static final Identifier[][] MODELS = buildModels();

	/**
	 * Which models actually baked, one flag per [style][material].
	 *
	 * Resolved on first use instead of on every item draw: the check is two
	 * map lookups, and the item model resolver runs it for every visible item
	 * in the inventory on every frame. A model that did load always loads
	 * again after a resource reload - it ships in our own jar - so a positive
	 * answer is safe to keep; a negative one is retried until it flips.
	 */
	private static final boolean[] MODEL_READY = new boolean[STYLES.length * MATERIALS.length];

	private final ChoiceSetting style = setting(new ChoiceSetting("style", 0, STYLES));
	private final BooleanSetting inspect = setting(new BooleanSetting("inspect", true));

	public CustomKnifeModule() {
		super(Category.VISUAL, "custom_knife");
	}

	private static Identifier[][] buildModels() {
		Identifier[][] ids = new Identifier[STYLES.length][MATERIALS.length];
		for (int s = 0; s < STYLES.length; s++) {
			for (int m = 0; m < MATERIALS.length; m++) {
				ids[s][m] = Identifier.fromNamespaceAndPath(NAMESPACE,
						MODEL_PREFIX + STYLES[s] + "_" + MATERIALS[m]);
			}
		}
		return ids;
	}

	public ChoiceSetting styleSetting() {
		return style;
	}

	public boolean inspectEnabled() {
		return inspect.value();
	}

	/** True when the given stack is one this module would draw as a knife. */
	public boolean inspectReady(ItemStack stack) {
		return isEnabled() && inspect.value()
				&& stack != null && !stack.isEmpty() && stack.is(ItemTags.SWORDS);
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
		KnifeInspect.start(styleId());
	}

	/** The selected style id, for the model path and the config file. */
	public String styleId() {
		return style.option();
	}

	/**
	 * Returns the stack to render: the given stack itself when nothing should
	 * change, or a copy carrying one of our item models.
	 *
	 * Called from the item model resolver, so it must stay cheap and must never
	 * throw: a broken asset or an unexpected stack has to leave the sword as it
	 * was, never break the render pass.
	 */
	public ItemStack swapFor(ItemStack stack, ItemOwner owner) {
		if (!isEnabled() || stack == null || stack.isEmpty() || !stack.is(ItemTags.SWORDS)) {
			return stack;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || (owner != null && owner != mc.player)) {
			// Somebody else's sword (another player, an item frame, a dropped
			// item). Only the local player's own gear is restyled.
			return stack;
		}
		Identifier current = stack.get(DataComponents.ITEM_MODEL);
		if (current != null && NAMESPACE.equals(current.getNamespace())
				&& current.getPath().startsWith(MODEL_PREFIX)) {
			// Already one of our knives. This is also what stops the resolver
			// from recursing: the swapped copy comes back through here once and
			// is left alone the second time.
			return stack;
		}
		int styleIndex = style.index();
		int material = materialIndex(stack);
		if (!isLoaded(mc, styleIndex, material)) {
			return stack;
		}
		ItemStack copy = stack.copy();
		copy.set(DataComponents.ITEM_MODEL, MODELS[styleIndex][material]);
		return copy;
	}

	/** True when the model was baked; a missing one would render as a magenta cube. */
	private static boolean isLoaded(Minecraft mc, int styleIndex, int material) {
		int slot = styleIndex * MATERIALS.length + material;
		if (MODEL_READY[slot]) {
			return true;
		}
		if (mc.getModelManager() == null) {
			// Too early (no resource reload finished yet): draw the vanilla
			// item and ask again on the next frame.
			return false;
		}
		Identifier model = MODELS[styleIndex][material];
		boolean ready = mc.getModelManager().getItemModel(model)
				!= mc.getModelManager().getItemModel(MISSING_PROBE);
		MODEL_READY[slot] = ready;
		return ready;
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
	}

	@Override
	public void saveConfig(JsonObject json) {
		json.addProperty("style", styleId());
		json.addProperty("inspect", inspect.value());
	}
}
