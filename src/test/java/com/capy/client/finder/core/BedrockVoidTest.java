package com.capy.client.finder.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class BedrockVoidTest {
	@Test
	void findsOneMissingCellAtItsLocalPosition() {
		boolean[] bedrock = new boolean[256];
		Arrays.fill(bedrock, true);
		bedrock[3 * 16 + 5] = false;

		List<int[]> holes = BedrockVoid.holes(bedrock);

		assertEquals(1, holes.size());
		assertArrayEquals(new int[]{5, 3}, holes.get(0));
	}
}
