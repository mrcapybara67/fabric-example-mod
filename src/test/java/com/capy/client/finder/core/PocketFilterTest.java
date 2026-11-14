package com.capy.client.finder.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PocketFilterTest {
	@Test
	void dropsAHundredChunkAreaAndKeepsSmallPatches() {
		Set<Long> flagged = new HashSet<>();
		for (int x = 0; x < 10; x++) {
			for (int z = 0; z < 10; z++) {
				flagged.add(Chunks.key(x, z));
			}
		}
		flagged.add(Chunks.key(-50, -50));
		flagged.add(Chunks.key(-30, -30));
		flagged.add(Chunks.key(-29, -30));
		flagged.add(Chunks.key(-30, -29));

		List<PocketFilter.Patch> patches = PocketFilter.patches(flagged, PocketFilter.maxSizeFor(4));

		assertEquals(2, patches.size());
		assertEquals(Set.of(1, 3), Set.copyOf(patches.stream().map(PocketFilter.Patch::size).toList()));
	}
}
