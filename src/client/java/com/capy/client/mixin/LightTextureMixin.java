package com.capy.client.mixin;

import com.capy.client.module.ModuleManager;
import com.capy.client.module.modules.FullbrightModule;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;

/**
 * Fullbright, applied where vanilla reads the brightness option.
 *
 * The obvious implementation — writing the value into {@code options.gamma()} —
 * cannot work in 1.21.11: {@code OptionInstance.set} runs the value through the
 * option's slider range, so every value above 1.0 is rejected and the option
 * keeps its previous one. Writing the private field instead would work, but the
 * game saves that field to {@code options.txt}, so a crash while the module was
 * on would leave the player with a permanently bright world after uninstalling.
 *
 * This hook leaves the option untouched. The lightmap computes its brightness
 * from the gamma option once per frame; the redirect swaps that single read for
 * the module's value. Nothing is stored, so switching the module off (or
 * removing it) restores exactly the brightness the player chose.
 *
 * The slice starts at the {@code gamma()} call, which is what keeps the
 * redirect on the gamma read and not on the darkness or lightning-flash options
 * the same method also reads.
 *
 * The read being replaced is typed {@code OptionInstance<Double>} at the call
 * site, so the value handed back has to be a {@code Double}: the bytecode after
 * the call is a checkcast to Double, and an Integer there is a
 * ClassCastException on the first frame the module is switched on.
 */
@Mixin(LightTexture.class)
public class LightTextureMixin {
	@Redirect(method = "updateLightTexture",
			slice = @Slice(from = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/Options;gamma()Lnet/minecraft/client/OptionInstance;")),
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;"))
	private Object capy$fullbrightGamma(OptionInstance<?> option) {
		FullbrightModule module = ModuleManager.get().get(FullbrightModule.class);
		if (module != null && module.isEnabled()) {
			// The option is an OptionInstance<Double>: the bytecode after the
			// replaced call is a checkcast to Double followed by doubleValue(),
			// so anything else here is a ClassCastException on the very first
			// frame the module is switched on.
			return Double.valueOf(module.gamma());
		}
		return option.get();
	}
}
