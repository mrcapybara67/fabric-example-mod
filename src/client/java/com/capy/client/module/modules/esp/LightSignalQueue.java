package com.capy.client.module.modules.esp;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;

/**
 * Classifies light data exactly as it arrived, before vanilla applies it.
 *
 * <p>The queue is bounded and drained under a wall-clock budget. A packet that
 * arrives after the queue is full is dropped rather than growing memory; a
 * missed light update costs a flag, never a frame.</p>
 */
public final class LightSignalQueue {
	public enum Kind { NORMAL, EMPTY_MASK, ZEROED_PRESENT, UNIFORM_MAX, UNKNOWN }

	private record Packet(int x, int z, ClientboundLightUpdatePacketData data) {
	}

	private static final int MAX_QUEUE = 4096;
	private static final int MAX_FINDINGS = 512;
	private static final Deque<Packet> QUEUE = new ArrayDeque<>();
	private static final Map<Long, Long> FINDINGS = new LinkedHashMap<>();
	private static BlockPos lastBreak;
	private static long lastBreakAt;

	private LightSignalQueue() {
	}

	public static void onChunkPacket(int x, int z, ClientboundLightUpdatePacketData data) {
		enqueue(x, z, data);
	}

	public static void onLightPacket(int x, int z, ClientboundLightUpdatePacketData data) {
		enqueue(x, z, data);
	}

	private static synchronized void enqueue(int x, int z, ClientboundLightUpdatePacketData data) {
		if (data == null || QUEUE.size() >= MAX_QUEUE) {
			return;
		}
		QUEUE.addLast(new Packet(x, z, data));
	}

	public static void noteLocalBreak(BlockPos pos, long now) {
		lastBreak = pos == null ? null : pos.immutable();
		lastBreakAt = now;
	}

	public static synchronized Map<Long, Long> findings() {
		return new LinkedHashMap<>(FINDINGS);
	}

	public static synchronized void drain(ClientLevel level, long now, long budgetNanos) {
		if (level == null) {
			QUEUE.clear();
			return;
		}
		long deadline = System.nanoTime() + Math.max(0, budgetNanos);
		int items = 0;
		while (!QUEUE.isEmpty() && items++ < 4096) {
			Packet packet = QUEUE.removeFirst();
			examine(level, packet, now);
			if (System.nanoTime() >= deadline) {
				break;
			}
		}
	}

	private static void examine(ClientLevel level, Packet packet, long now) {
		if (suppressed(packet.x, packet.z, now)) {
			return;
		}
		ClientboundLightUpdatePacketData data = packet.data();
		int minSection = level.getMinSectionY();
		boolean bad = belowOne(data.getSkyYMask(), data.getSkyUpdates(),
				data.getEmptySkyYMask(), minSection)
				|| belowOne(data.getBlockYMask(), data.getBlockUpdates(),
				data.getEmptyBlockYMask(), minSection);
		if (bad) {
			FINDINGS.put(HeatMap.key(packet.x, packet.z), now);
			while (FINDINGS.size() > MAX_FINDINGS) {
				FINDINGS.remove(FINDINGS.keySet().iterator().next());
			}
		}
	}

	private static boolean belowOne(java.util.BitSet mask, java.util.List<byte[]> updates,
			java.util.BitSet emptyMask, int minSection) {
		int update = 0;
		for (int bit = mask.nextSetBit(0); bit >= 0; bit = mask.nextSetBit(bit + 1)) {
			if (update >= updates.size()) {
				break;
			}
			byte[] bytes = updates.get(update++);
			if (emptyMask.get(bit)) {
				continue;
			}
			if (minSection + bit >= 1) {
				continue;
			}
			Kind kind = classify(bytes);
			if (kind == Kind.ZEROED_PRESENT || kind == Kind.UNIFORM_MAX) {
				return true;
			}
		}
		return false;
	}

	/** Pure classifier used by the light tests. */
	public static Kind classify(byte[] bytes) {
		if (bytes == null || bytes.length == 0) {
			return Kind.UNKNOWN;
		}
		boolean zero = true;
		boolean full = true;
		for (byte value : bytes) {
			int b = value & 0xFF;
			if (b != 0) {
				zero = false;
				if (b != 0xFF) {
					full = false;
				}
			}
		}
		if (zero) {
			return Kind.ZEROED_PRESENT;
		}
		if (full) {
			return Kind.UNIFORM_MAX;
		}
		return Kind.NORMAL;
	}

	private static boolean suppressed(int x, int z, long now) {
		if (lastBreak == null || now - lastBreakAt > 4_000_000_000L) {
			return false;
		}
		return Math.abs((lastBreak.getX() >> 4) - x) <= 1
				&& Math.abs((lastBreak.getZ() >> 4) - z) <= 1;
	}

	public static synchronized void clear() {
		QUEUE.clear();
		FINDINGS.clear();
		lastBreak = null;
		lastBreakAt = 0L;
	}
}
