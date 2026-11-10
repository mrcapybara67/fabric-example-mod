package com.capy.client.mixin;

import net.minecraft.client.player.ClientInput;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read access to the movement vector that ClientInput keeps protected. */
@Mixin(ClientInput.class)
public interface ClientInputAccessor {
	@Accessor("moveVector")
	Vec2 capy$moveVector();
}
