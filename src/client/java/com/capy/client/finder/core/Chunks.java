package com.capy.client.finder.core;

/** Same bit layouts as vanilla ChunkPos.toLong and BlockPos.asLong. */
public final class Chunks {
	private Chunks() {
	}

	public static long key(int cx, int cz) {
		return (cx & 0xFFFFFFFFL) | ((cz & 0xFFFFFFFFL) << 32);
	}

	public static int x(long key) {
		return (int) key;
	}

	public static int z(long key) {
		return (int) (key >>> 32);
	}

	public static long block(int x, int y, int z) {
		return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
	}

	public static int blockX(long k) {
		return (int) (k >> 38);
	}

	public static int blockZ(long k) {
		return (int) (k << 26 >> 38);
	}

	public static int blockY(long k) {
		return (int) (k << 52 >> 52);
	}
}
