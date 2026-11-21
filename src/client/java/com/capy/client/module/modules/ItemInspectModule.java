package com.capy.client.module.modules;

import com.capy.client.config.ConfigValues;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.Category;
import com.capy.client.module.Configurable;
import com.capy.client.module.Module;
import com.capy.client.module.ModuleManager;
import com.capy.client.module.SliderSetting;
import com.capy.client.module.Tickable;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Item Inspect — a staged first-person inspection for the held weapon.
 *
 * <p>No custom model and no texture: the ordinary vanilla item is animated in
 * hand space, so resource packs, enchantment glints and every tool material
 * keep working. The animation starts and ends at the vanilla hand pose; a
 * swing, use, slot change or screen cancels it with a short return instead of a
 * snap.</p>
 *
 * <p>Every sword uses the sword profile, every spear and trident the spear
 * profile, and bows, crossbows, maces and any item carrying the vanilla
 * {@code WEAPON} component have their own profile. The per-type switches only
 * decide which of those profiles the player wants to see.</p>
 */
public class ItemInspectModule extends Module implements Tickable, Configurable {
	public enum Tool {
		SWORD(1_650_000_000L),
		SPEAR(1_950_000_000L),
		AXE(1_750_000_000L),
		PICKAXE(1_650_000_000L),
		SHOVEL(1_450_000_000L),
		HOE(1_450_000_000L),
		MACE(1_900_000_000L),
		RANGED(1_800_000_000L),
		GENERIC(1_650_000_000L);

		private final long durationNanos;

		Tool(long durationNanos) {
			this.durationNanos = durationNanos;
		}

		public long durationNanos() {
			return durationNanos;
		}
	}

	private final BooleanSetting enabled = setting(new BooleanSetting("enabled", true));
	private final SliderSetting speed = setting(new SliderSetting("speed", 100, 50, 200));
	private final BooleanSetting sword = setting(new BooleanSetting("sword", true));
	private final BooleanSetting axe = setting(new BooleanSetting("axe", true));
	private final BooleanSetting pickaxe = setting(new BooleanSetting("pickaxe", true));
	private final BooleanSetting shovel = setting(new BooleanSetting("shovel", true));
	private final BooleanSetting hoe = setting(new BooleanSetting("hoe", true));
	private final BooleanSetting spear = setting(new BooleanSetting("spear", true));
	private final BooleanSetting mace = setting(new BooleanSetting("mace", true));
	private final BooleanSetting ranged = setting(new BooleanSetting("ranged", true));
	private final BooleanSetting otherWeapon = setting(new BooleanSetting("other_weapon", true));
	private long cooldownUntil;

	public ItemInspectModule() {
		super(Category.VISUAL, "item_inspect");
	}

	public boolean animationEnabled() {
		return enabled.value();
	}

	public float speedFactor() {
		return Math.max(0.5f, Math.min(2f, (float) speed.real() / 100f));
	}

	public Tool toolOf(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		// Spears first: a trident is a spear for inspection purposes, but it is
		// not a sword or an axe even though it is a weapon.
		if (stack.is(ItemTags.SPEARS) || stack.is(ItemTags.TRIDENT_ENCHANTABLE)
				|| stack.is(Items.TRIDENT)) {
			return spear.value() ? Tool.SPEAR : null;
		}
		if (stack.is(ItemTags.MACE_ENCHANTABLE) || stack.is(Items.MACE)) {
			return mace.value() ? Tool.MACE : null;
		}
		if (stack.is(ItemTags.BOW_ENCHANTABLE) || stack.is(ItemTags.CROSSBOW_ENCHANTABLE)) {
			return ranged.value() ? Tool.RANGED : null;
		}
		if (stack.is(ItemTags.SWORDS)) {
			return sword.value() ? Tool.SWORD : null;
		}
		if (stack.is(ItemTags.AXES)) {
			return axe.value() ? Tool.AXE : null;
		}
		if (stack.is(ItemTags.PICKAXES)) {
			return pickaxe.value() ? Tool.PICKAXE : null;
		}
		if (stack.is(ItemTags.SHOVELS)) {
			return shovel.value() ? Tool.SHOVEL : null;
		}
		if (stack.is(ItemTags.HOES)) {
			return hoe.value() ? Tool.HOE : null;
		}
		// Last, so every specific profile above wins. WEAPON covers vanilla
		// weapons and modded weapons that use the vanilla component; the tag
		// catches the remaining weapon-like items that only declare the
		// enchantment category.
		if (stack.has(DataComponents.WEAPON) || stack.is(ItemTags.WEAPON_ENCHANTABLE)) {
			return otherWeapon.value() ? Tool.GENERIC : null;
		}
		return null;
	}

	public void triggerInspect() {
		if (!isEnabled() || !enabled.value()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.player == null || mc.screen != null) {
			return;
		}
		FreecamModule freecam = ModuleManager.get().get(FreecamModule.class);
		FreelookModule freelook = ModuleManager.get().get(FreelookModule.class);
		if ((freecam != null && freecam.isActive()) || (freelook != null && freelook.isActive())) {
			ItemInspectPose.cancel();
			return;
		}
		if (System.nanoTime() < cooldownUntil) {
			return;
		}
		ItemStack stack = mc.player.getMainHandItem();
		Tool tool = toolOf(stack);
		if (tool == null || mc.player.getAttackAnim(1f) > 0.01f || mc.player.isUsingItem()) {
			return;
		}
		cooldownUntil = System.nanoTime() + 350_000_000L;
		ItemInspectPose.start(tool, speedFactor(), stack);
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!isEnabled() || !enabled.value()) {
			ItemInspectPose.cancel();
			return;
		}
		if (mc == null || mc.player == null || mc.screen != null) {
			ItemInspectPose.cancel();
			return;
		}
		FreecamModule freecam = ModuleManager.get().get(FreecamModule.class);
		FreelookModule freelook = ModuleManager.get().get(FreelookModule.class);
		if ((freecam != null && freecam.isActive()) || (freelook != null && freelook.isActive())
				|| mc.player.isUsingItem() || mc.player.getAttackAnim(1f) > 0.01f
				|| toolOf(mc.player.getMainHandItem()) == null) {
			ItemInspectPose.cancel();
		}
	}

	@Override
	protected void onToggle(boolean state) {
		if (!state) {
			ItemInspectPose.cancel();
		}
	}

	@Override
	public void loadConfig(JsonObject json) {
		enabled.set(ConfigValues.boolValue(json, "enabled", true));
		speed.set(ConfigValues.intValue(json, "speed", 100));
		sword.set(ConfigValues.boolValue(json, "sword", true));
		axe.set(ConfigValues.boolValue(json, "axe", true));
		pickaxe.set(ConfigValues.boolValue(json, "pickaxe", true));
		shovel.set(ConfigValues.boolValue(json, "shovel", true));
		hoe.set(ConfigValues.boolValue(json, "hoe", true));
		spear.set(ConfigValues.boolValue(json, "spear", true));
		mace.set(ConfigValues.boolValue(json, "mace", true));
		ranged.set(ConfigValues.boolValue(json, "ranged", true));
		otherWeapon.set(ConfigValues.boolValue(json, "other_weapon", true));
	}

	@Override
	public void saveConfig(JsonObject json) {
		json.addProperty("enabled", enabled.value());
		json.addProperty("speed", speed.get());
		json.addProperty("sword", sword.value());
		json.addProperty("axe", axe.value());
		json.addProperty("pickaxe", pickaxe.value());
		json.addProperty("shovel", shovel.value());
		json.addProperty("hoe", hoe.value());
		json.addProperty("spear", spear.value());
		json.addProperty("mace", mace.value());
		json.addProperty("ranged", ranged.value());
		json.addProperty("other_weapon", otherWeapon.value());
	}
}
