package com.capy.client.stealth;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;

/** Removes the client's own namespace and Fabric's channels from an announcement. */
public final class ChannelFilter {
	private ChannelFilter() {
	}

	public static List<Identifier> filter(List<Identifier> channels) {
		List<Identifier> kept = new ArrayList<>(channels.size());
		for (Identifier channel : channels) {
			if (channel == null) {
				continue;
			}
			String namespace = channel.getNamespace();
			if (namespace.startsWith("fabric") || namespace.startsWith("capy")) {
				continue;
			}
			kept.add(channel);
		}
		return kept;
	}
}
