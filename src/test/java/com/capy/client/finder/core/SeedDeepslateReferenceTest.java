package com.capy.client.finder.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.junit.jupiter.api.Test;

class SeedDeepslateReferenceTest {
	@Test
	void largeWorldCoordinatesMatchTheVanillaGenerator() {
		long worldSeed = -987654321L;
		SeedDeepslate ours = new SeedDeepslate(worldSeed);
		PositionalRandomFactory factory = new XoroshiroRandomSource(worldSeed)
				.forkPositional()
				.fromHashOf("minecraft:deepslate")
				.forkPositional();

		int[] coordinates = {0, 1000, -1000, 100000, -100000};
		for (int y = 1; y < 8; y++) {
			for (int x : coordinates) {
				for (int z : coordinates) {
					assertEquals(factory.at(x, y, z).nextFloat(), ours.floatAt(x, y, z), 0.0f,
							"deepslate positional random at " + x + "," + y + "," + z);
				}
			}
		}
	}

	@Test
	void positionalRandomMatchesTheVanillaGenerator() {
		long worldSeed = 123456789L;
		SeedDeepslate ours = new SeedDeepslate(worldSeed);
		PositionalRandomFactory factory = new XoroshiroRandomSource(worldSeed)
				.forkPositional()
				.fromHashOf("minecraft:deepslate")
				.forkPositional();

		for (int y = 1; y < 8; y++) {
			for (int x = -4; x <= 4; x++) {
				for (int z = -4; z <= 4; z++) {
					float expected = factory.at(x, y, z).nextFloat();
					assertEquals(expected, ours.floatAt(x, y, z), 0.0f,
							"deepslate positional random at " + x + "," + y + "," + z);
				}
			}
		}
	}
}
