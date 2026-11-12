package com.capy.client.module.modules.esp;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Classifies light data exactly as it arrived, before vanilla applies it.
 *
 * <p>The packet's non-empty mask pairs one-for-one with its update list. The
 * empty mask is disjoint from that list (verified against the 1.21.11 packet
 * bytecode), so the update cursor must only advance for non-empty bits.</p>
 *
 * <p>Light sections include one section below the world, so bit 0 is
 * {@code minLightSection}, which is normally {@code minSectionY - 1}.</p>
 */
public final class LightSignalQueue {
	public enum Kind { NORMAL, EMPTY_MASK, ZEROED_PRESENT, UNIFORM_MAX, UNKNOWN }

	private record Packet(int x, int z, ClientboundLightUpdatePacketData data, boolean chunkLoad) {
	}

	private static final Logger LOGGER = LoggerFactory.getLogger("capy-light");
	private static final int MAX_QUEUE = 4096;
	private static final int MAX_FINDINGS = 512;
	private static final int MAX_SNAPSHOTS = 8192;
	private static final int MAX_SECTIONS = 64;
	private static final AtomicBoolean CHUNK_HOOK_LOGGED = new AtomicBoolean();
	private static final AtomicBoolean UPDATE_HOOK_LOGGED = new AtomicBoolean();
	private static final Deque<Packet> QUEUE = new ArrayDeque<>();
	private static final Map<Long, Long> FINDINGS = new LinkedHashMap<>();
	private static final Map<Long, Snapshot> SNAPSHOTS = new LinkedHashMap<>();
	private static BlockPos lastBreak;
	private static long lastBreakAt;

	public static final class Snapshot {
		final byte[] sky = new byte[MAX_SECTIONS];
		final byte[] block = new byte[MAX_SECTIONS];
		boolean skyFilled;
		boolean blockFilled;
	}

	private LightSignalQueue() {
	}

	public static void onChunkPacket(int x, int z, ClientboundLightUpdatePacketData data) {
		if (CHUNK_HOOK_LOGGED.compareAndSet(false, true) && ChunkFinderModule.debugLogging()) {
			LOGGER.info("Capy light hook fired: chunk-with-light at {},{}", x, z);
		}
		enqueue(x, z, data, true);
	}

	public static void onLightPacket(int x, int z, ClientboundLightUpdatePacketData data) {
		if (UPDATE_HOOK_LOGGED.compareAndSet(false, true) && ChunkFinderModule.debugLogging()) {
			LOGGER.info("Capy light hook fired: light update at {},{}", x, z);
		}
		enqueue(x, z, data, false);
	}

	private static synchronized void enqueue(int x, int z, ClientboundLightUpdatePacketData data, boolean chunkLoad) {
		if (data == null || QUEUE.size() >= MAX_QUEUE) {
			return;
		}
		QUEUE.addLast(new Packet(x, z, data, chunkLoad));
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
		long deadline = System.nanoTime() + Math.max(0L, budgetNanos);
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
		int minLight = level.getLightEngine().getMinLightSection();
		int count = Math.min(MAX_SECTIONS, level.getLightEngine().getLightSectionCount());
		long key = HeatMap.key(packet.x, packet.z);
		Snapshot snapshot = SNAPSHOTS.get(key);
		boolean first = snapshot == null;
		if (first) {
			snapshot = new Snapshot();
			SNAPSHOTS.put(key, snapshot);
			while (SNAPSHOTS.size() > MAX_SNAPSHOTS) {
				SNAPSHOTS.remove(SNAPSHOTS.keySet().iterator().next());
			}
		}
		ClientboundLightUpdatePacketData data = packet.data();
		if (ChunkFinderModule.debugLogging()) {
			LOGGER.info("Capy light masks {},{} sky={} emptySky={} skyUpdates={} block={} emptyBlock={} blockUpdates={}",
					packet.x, packet.z, data.getSkyYMask().cardinality(),
					data.getEmptySkyYMask().cardinality(), data.getSkyUpdates().size(),
					data.getBlockYMask().cardinality(), data.getEmptyBlockYMask().cardinality(),
					data.getBlockUpdates().size());
		}
		boolean flagLoad = first && packet.chunkLoad && ChunkFinderModule.flagOnLoad();
		boolean bad = apply(snapshot, true, minLight, count, data.getSkyYMask(), data.getSkyUpdates(),
				data.getEmptySkyYMask(), flagLoad)
				| apply(snapshot, false, minLight, count, data.getBlockYMask(), data.getBlockUpdates(),
				data.getEmptyBlockYMask(), flagLoad);
		if (bad) {
			FINDINGS.put(key, now);
			while (FINDINGS.size() > MAX_FINDINGS) {
				FINDINGS.remove(FINDINGS.keySet().iterator().next());
			}
		}
	}

	/**
	 * Applies one layer of a packet. Pure enough for tests: it only touches the
	 * supplied snapshot and returns whether a section below Y 1 changed from a
	 * non-bad class to {@link Kind#ZEROED_PRESENT} or {@link Kind#UNIFORM_MAX}.
	 */
	public static boolean apply(Snapshot snapshot, boolean skyLayer, int minLightSection, int count,
			BitSet mask, List<byte[]> updates, BitSet empty, boolean flagOnLoad) {
		boolean found = false;
		int update = 0;
		byte[] layer = skyLayer ? snapshot.sky : snapshot.block;
		boolean hadFilled = skyLayer ? snapshot.skyFilled : snapshot.blockFilled;
		for (int bit = 0; bit < count; bit++) {
			Kind current;
			if (empty.get(bit)) {
				current = Kind.EMPTY_MASK;
			} else if (mask.get(bit)) {
				current = update < updates.size() ? classify(updates.get(update)) : Kind.UNKNOWN;
				update++;
			} else {
				continue;
			}
			byte before = layer[bit];
			layer[bit] = (byte) current.ordinal();
			int worldSection = minLightSection + bit;
			boolean bad = current == Kind.ZEROED_PRESENT || current == Kind.UNIFORM_MAX;
			boolean changed = !hadFilled || before != (byte) current.ordinal();
			if (worldSection < 1 && bad && (flagOnLoad || hadFilled && changed)) {
				found = true;
			}
		}
		if (skyLayer) {
			snapshot.skyFilled = true;
		} else {
			snapshot.blockFilled = true;
		}
		return found;
	}

	/** Tests use the same layer table as the packet path. */
	public static Snapshot newSnapshot() {
		return new Snapshot();
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
		SNAPSHOTS.clear();
		lastBreak = null;
		lastBreakAt = 0L;
	}
}
