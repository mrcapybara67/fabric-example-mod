package com.capy.client.module.modules.esp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.capy.client.finder.core.Chunks;
import com.capy.client.finder.core.TerrainPlate;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TerrainHeightsTest {
	@Test
	void peekNeverSamplesAndEnsureRefreshesAtMostEveryFortyTicks() {
		AtomicInteger samples = new AtomicInteger();
		TerrainHeights heights = new TerrainHeights((level, x, z) -> {
			samples.incrementAndGet();
			return 70;
		});
		long key = Chunks.key(2, -3);

		int[] first = heights.ensure(null, 0, 319, key, 100);
		assertEquals(TerrainPlate.CELLS * TerrainPlate.CELLS, samples.get());
		int[] again = heights.ensure(null, 0, 319, key, 139);
		assertEquals(TerrainPlate.CELLS * TerrainPlate.CELLS, samples.get());
		assertEquals(first, again);

		for (int i = 0; i < 10; i++) {
			assertEquals(first, heights.peek(key));
		}
		assertEquals(TerrainPlate.CELLS * TerrainPlate.CELLS, samples.get());

		heights.ensure(null, 0, 319, key, 140);
		assertEquals(2 * TerrainPlate.CELLS * TerrainPlate.CELLS, samples.get());
	}
}
