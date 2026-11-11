package com.capy.client.module.modules;

import com.capy.client.module.modules.antiadmin.ServerProfileMatcher;
import net.minecraft.client.Minecraft;

/** Shared host lookup for the movement modules, kept out of their hot loops. */
final class MovementKickGuardHost {
	private MovementKickGuardHost() {
	}

	static String host() {
		Minecraft mc = Minecraft.getInstance();
		String host = ServerProfileMatcher.hostOf(mc);
		return host == null || host.isBlank() ? "generic" : host;
	}
}
