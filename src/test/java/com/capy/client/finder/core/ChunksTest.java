package com.capy.client.finder.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ChunksTest {
	@Test
	void chunkKeysRoundTrip() {
		assertEquals(0, Chunks.x(Chunks.key(0, 0)));
		assertEquals(0, Chunks.z(Chunks.key(0, 0)));
		assertEquals(-17, Chunks.x(Chunks.key(-17, 31)));
		assertEquals(31, Chunks.z(Chunks.key(-17, 31)));
		assertEquals(Integer.MIN_VALUE, Chunks.x(Chunks.key(Integer.MIN_VALUE, Integer.MAX_VALUE)));
		assertEquals(Integer.MAX_VALUE, Chunks.z(Chunks.key(Integer.MIN_VALUE, Integer.MAX_VALUE)));
	}

	@Test
	void blockKeysRoundTrip() {
		long key = Chunks.block(-123456, 63, 987654);
		assertEquals(-123456, Chunks.blockX(key));
		assertEquals(63, Chunks.blockY(key));
		assertEquals(987654, Chunks.blockZ(key));
	}
}
