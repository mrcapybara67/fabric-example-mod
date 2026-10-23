package com.capy.client.mixin;

import com.capy.client.module.ModuleManager;
import com.capy.client.module.modules.CustomKnifeModule;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands every item layer render to {@link CustomKnifeModule} first.
 *
 * {@code appendItemLayers} is the single funnel every item goes through: the
 * held item, the third person hand, the hotbar, inventory slots and containers
 * all reach it through {@code updateForTopItem} or {@code updateForLiving}.
 * Injecting there means one point to keep correct instead of one per renderer,
 * and the owner argument is available right in the signature, which is what
 * keeps other players' swords vanilla.
 *
 * The replacement stack is rendered by calling the original method again with
 * the copy. That second call re-enters this injection, but the copy already
 * carries one of our model ids, so {@code swapFor} returns it unchanged and
 * the vanilla body runs. No recursion guard state is needed, and the depth is
 * one by construction.
 */
@Mixin(ItemModelResolver.class)
public class ItemModelResolverMixin {
	@Inject(method = "appendItemLayers", at = @At("HEAD"), cancellable = true)
	private void capy$customKnife(ItemStackRenderState state, ItemStack stack, ItemDisplayContext context,
			Level level, ItemOwner owner, int seed, CallbackInfo ci) {
		CustomKnifeModule module = ModuleManager.get().get(CustomKnifeModule.class);
		if (module == null) {
			return;
		}
		ItemStack swapped = module.swapFor(stack, owner);
		if (swapped == stack) {
			return;
		}
		((ItemModelResolver) (Object) this).appendItemLayers(state, swapped, context, level, owner, seed);
		ci.cancel();
	}
}
