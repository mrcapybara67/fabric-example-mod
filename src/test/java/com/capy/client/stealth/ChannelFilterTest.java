package com.capy.client.stealth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class ChannelFilterTest {
	@Test
	void removesFabricAndCapyButKeepsVanillaAndOtherMods() {
		List<Identifier> input = List.of(
				Identifier.fromNamespaceAndPath("minecraft", "brand"),
				Identifier.fromNamespaceAndPath("fabric-networking-api-v1", "register"),
				Identifier.fromNamespaceAndPath("capy", "private"),
				Identifier.fromNamespaceAndPath("create", "ponder"));
		assertEquals(List.of(
				Identifier.fromNamespaceAndPath("minecraft", "brand"),
				Identifier.fromNamespaceAndPath("create", "ponder")), ChannelFilter.filter(input));
	}

	@Test
	void nullChannelsAreIgnored() {
		assertEquals(List.of(Identifier.fromNamespaceAndPath("minecraft", "brand")),
				ChannelFilter.filter(java.util.Arrays.asList(null,
						Identifier.fromNamespaceAndPath("minecraft", "brand"))));
	}
}
