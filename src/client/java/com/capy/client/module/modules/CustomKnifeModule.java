package com.capy.client.module.modules;

import com.capy.client.config.ConfigValues;
import com.capy.client.module.Category;
import com.capy.client.module.ChoiceSetting;
import com.capy.client.module.Configurable;
import com.capy.client.module.Module;
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
 * The models themselves are ordinary handheld item models with a 16x16 sprite,
 * the same pipeline every vanilla sword uses, so the knife sits in the hand at
 * the same angle and scale as the sword it replaces. One sprite per style and
 * sword material means a wooden sword keeps looking wooden and a netherite one
 * keeps looking like netherite.
 *
 * Scope: only the local player's own items are changed. {@code owner} is the
 * entity the item is being rendered for, so another player's sword is left
 * alone, and so is an item lying on the ground or in an item frame (those are
 * rendered for the entity that owns them, not for the player).
 */
public class CustomKnifeModule extends Module implements Configurable {
	/** The knife styles that ship with the client, in menu order. */
	public static final String[] STYLES = {"butterfly", "karambit", "cleaver", "dagger"};

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

	private final ChoiceSetting style = setting(new ChoiceSetting("style", 0, STYLES));

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
		Identifier model = MODELS[style.index()][materialIndex(stack)];
		if (!isLoaded(mc, model)) {
			return stack;
		}
		ItemStack copy = stack.copy();
		copy.set(DataComponents.ITEM_MODEL, model);
		return copy;
	}

	/** True when the model was baked; a missing one would render as a magenta cube. */
	private static boolean isLoaded(Minecraft mc, Identifier model) {
		return mc.getModelManager() != null
				&& mc.getModelManager().getItemModel(model) != mc.getModelManager().getItemModel(MISSING_PROBE);
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
	}

	@Override
	public void saveConfig(JsonObject json) {
		json.addProperty("style", styleId());
	}
}
