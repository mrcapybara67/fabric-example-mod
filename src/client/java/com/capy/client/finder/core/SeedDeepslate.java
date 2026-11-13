package com.capy.client.finder.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Natural deepslate near the bottom of the overworld follows a seeded rule: y <= 0 always deepslate, y >= 8 never,
 * otherwise deepslate when positionalRandom.at(x, y, z).nextFloat() < 1 - y / 8. The positional random is Xoroshiro:
 * seed -> forkPositional -> fromHashOf("minecraft:deepslate") -> forkPositional.
 *
 * <p>The constants and the Xoroshiro128++ transition below match the 1.21.11
 * {@code RandomSupport} and {@code XoroshiroRandomSource} bytecode. The vertical
 * gradient is the overworld deepslate replacement rule. This class only judges
 * the overworld band; the caller decides whether the dimension uses it.</p>
 */
public final class SeedDeepslate {
	private static final long GOLDEN_RATIO = 0x9E3779B97F4A7C15L;
	private static final long SILVER_RATIO = 0x6A09E667F3BCC909L;

	private final long factoryLo;
	private final long factoryHi;

	public SeedDeepslate(long worldSeed) {
		long lo = worldSeed ^ SILVER_RATIO;
		long hi = lo + GOLDEN_RATIO;
		Xoro root = new Xoro(mix(lo), mix(hi));
		long positionalLo = root.nextLong();
		long positionalHi = root.nextLong();
		long[] hash = md5("minecraft:deepslate");
		Xoro named = new Xoro(hash[0] ^ positionalLo, hash[1] ^ positionalHi);
		this.factoryLo = named.nextLong();
		this.factoryHi = named.nextLong();
	}

	public boolean natural(int x, int y, int z) {
		if (y <= 0) {
			return true;
		}
		if (y >= 8) {
			return false;
		}
		return floatAt(x, y, z) < 1.0 - y / 8.0;
	}

	/** A deepslate block at y 1..7 where natural() is false is not explained by the natural gradient. */
	public boolean suspicious(int x, int y, int z) {
		return y >= 1 && y < 8 && !natural(x, y, z);
	}

	float floatAt(int x, int y, int z) {
		long l = (long) (x * 3129871) ^ (long) z * 116129781L ^ (long) y;
		l = l * l * 42317861L + l * 11L;
		l >>= 16;
		return (float) (int) (new Xoro(l ^ factoryLo, factoryHi).nextLong() >>> 40) * 5.9604645E-8F;
	}

	private static long mix(long value) {
		long v = value;
		v = (v ^ (v >>> 30)) * 0xBF58476D1CE4E5B9L;
		v = (v ^ (v >>> 27)) * 0x94D049BB133111EBL;
		return v ^ (v >>> 31);
	}

	private static long[] md5(String value) {
		try {
			byte[] bytes = MessageDigest.getInstance("MD5")
					.digest(value.getBytes(StandardCharsets.UTF_8));
			ByteBuffer buffer = ByteBuffer.wrap(bytes);
			return new long[]{buffer.getLong(), buffer.getLong()};
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("MD5 is part of every Java runtime", impossible);
		}
	}

	/** Xoroshiro128++ with the same transition order as vanilla's generator. */
	private static final class Xoro {
		private long lo;
		private long hi;

		Xoro(long lo, long hi) {
			this.lo = lo;
			this.hi = hi;
		}

		long nextLong() {
			long s0 = lo;
			long s1 = hi;
			long result = Long.rotateLeft(s0 + s1, 17) + s0;
			s1 ^= s0;
			lo = Long.rotateLeft(s0, 49) ^ s1 ^ (s1 << 21);
			hi = Long.rotateLeft(s1, 28);
			return result;
		}
	}
}
