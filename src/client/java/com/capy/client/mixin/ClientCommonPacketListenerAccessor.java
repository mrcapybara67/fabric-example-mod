package com.capy.client.mixin;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the brand the server sent with its join payload. */
@Mixin(ClientCommonPacketListenerImpl.class)
public interface ClientCommonPacketListenerAccessor {
	@Accessor("serverBrand")
	String capy$serverBrand();
}
