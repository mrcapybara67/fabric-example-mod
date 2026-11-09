package com.capy.client.mixin;

import com.capy.client.module.ModuleManager;
import com.capy.client.module.modules.FreecamModule;
import com.capy.client.util.Guard;
import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the body still while the camera is detached.
 *
 * <p>The old freeze ran at the end of the module tick, after vanilla had
 * already copied the keys into the player's movement. This hook runs at the end
 * of the input update itself: the game has read the keys, and the body sees an
 * empty input before its movement is calculated. The Freecam camera reads the
 * raw options, so it still flies on the same keys.</p>
 */
@Mixin(ClientInput.class)
public abstract class FreecamInputMixin {
	@Shadow
	public Input keyPresses;

	@Shadow
	public Vec2 moveVector;

	@Inject(method = "tick", at = @At("TAIL"))
	private void capy$freezeBodyInput(CallbackInfo ci) {
		try {
			FreecamModule freecam = ModuleManager.get().get(FreecamModule.class);
			if (freecam != null && freecam.isEnabled() && freecam.freezeBodyEnabled()) {
				this.keyPresses = Input.EMPTY;
				this.moveVector = Vec2.ZERO;
			}
		} catch (Throwable failure) {
			Guard.report("freecam input", failure);
		}
	}
}
