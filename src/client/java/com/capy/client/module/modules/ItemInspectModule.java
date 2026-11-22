package com.capy.client.module.modules;

import com.capy.client.module.Category;
import com.capy.client.module.Module;
import com.capy.client.module.ModuleManager;
import com.capy.client.module.Tickable;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;/**
 * Item Inspect — a CS2-style inspection for the held weapon.
 *
 * <p>No custom model and no texture: the ordinary vanilla item is animated in
 * hand space, so resource packs, enchantment glints and every tool material
 * keep working. The animation starts and ends at the vanilla hand pose; a
 * swing, use, slot change or screen cancels it with a short return instead of a
 * snap.</p>
 *
 * <p>Every press of C chooses one of three rotation variants in
 * {@link ItemInspectPose}. Those three variants are always active while the
 * module is enabled; there is no animation switch, speed slider or per-weapon
 * filter to configure.</p>
 */
public class ItemInspectModule extends Module implements Tickable {
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

	private static final long COOLDOWN_NANOS = 350_000_000L;
	private long cooldownUntil;

	public ItemInspectModule() {
		super(Category.VISUAL, "item_inspect");
	}

	public Tool toolOf(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		// Spears first: a trident is a spear for inspection purposes, but it is
		// not a sword or an axe even though it is a weapon.
		if (stack.is(ItemTags.SPEARS) || stack.is(ItemTags.TRIDENT_ENCHANTABLE)
				|| stack.is(Items.TRIDENT)) {
			return Tool.SPEAR;
		}
		if (stack.is(ItemTags.MACE_ENCHANTABLE) || stack.is(Items.MACE)) {
			return Tool.MACE;
		}
		if (stack.is(ItemTags.BOW_ENCHANTABLE) || stack.is(ItemTags.CROSSBOW_ENCHANTABLE)) {
			return Tool.RANGED;
		}
		if (stack.is(ItemTags.SWORDS)) {
			return Tool.SWORD;
		}
		if (stack.is(ItemTags.AXES)) {
			return Tool.AXE;
		}
		if (stack.is(ItemTags.PICKAXES)) {
			return Tool.PICKAXE;
		}
		if (stack.is(ItemTags.SHOVELS)) {
			return Tool.SHOVEL;
		}
		if (stack.is(ItemTags.HOES)) {
			return Tool.HOE;
		}
		// Last, so every specific profile above wins. WEAPON covers vanilla
		// weapons and modded weapons that use the vanilla component; the tag
		// catches the remaining weapon-like items that only declare the
		// enchantment category.
		if (stack.has(DataComponents.WEAPON) || stack.is(ItemTags.WEAPON_ENCHANTABLE)) {
			return Tool.GENERIC;
		}
		return null;
	}

	public void triggerInspect() {
		if (!isEnabled()) {
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
		cooldownUntil = System.nanoTime() + COOLDOWN_NANOS;
		ItemInspectPose.start(tool, stack);
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!isEnabled()) {
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
}
