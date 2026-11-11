package com.capy.client.module.modules.esp;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * A time-budgeted chunk scan queue.
 *
 * <p>A chunk is held as a key plus a ready tick, so a chunk that just arrived
 * is not scanned before its data has settled. The same key cannot be queued
 * twice. Unloaded chunks are dropped rather than scanned from an empty cache.</p>
 */
public final class ScanScheduler {
	private static final long DEFAULT_SETTLE_TICKS = 8L;
	private final Map<Long, Long> readyAt = new LinkedHashMap<>();
	private final Map<Long, Long> queuedAt = new LinkedHashMap<>();

	public void offer(ChunkPos pos, long nowTick) {
		offer(pos.toLong(), nowTick, DEFAULT_SETTLE_TICKS);
	}

	public void offer(long key, long nowTick, long settleTicks) {
		if (queuedAt.containsKey(key)) {
			return;
		}
		queuedAt.put(key, nowTick);
		readyAt.put(key, nowTick + Math.max(0, settleTicks));
	}

	public void markDirty(ChunkPos pos, long nowTick) {
		offer(pos.toLong(), nowTick, 2L);
	}

	public void remove(long key) {
		queuedAt.remove(key);
		readyAt.remove(key);
	}

	public void clear() {
		queuedAt.clear();
		readyAt.clear();
	}

	public int size() {
		return queuedAt.size();
	}

	/**
	 * Drains at most {@code maxChunks} and at most {@code budgetNanos}.
	 * Returns the number of chunks visited.
	 */
	public int drain(ClientLevel level, long nowTick, long budgetNanos, int maxChunks,
			Consumer<LevelChunk> consumer) {
		long deadline = System.nanoTime() + Math.max(0, budgetNanos);
		int visited = 0;
		Iterator<Map.Entry<Long, Long>> iterator = readyAt.entrySet().iterator();
		while (iterator.hasNext() && visited < maxChunks) {
			Map.Entry<Long, Long> entry = iterator.next();
			long key = entry.getKey();
			if (entry.getValue() > nowTick) {
				continue;
			}
			LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
			iterator.remove();
			queuedAt.remove(key);
			if (chunk == null) {
				continue;
			}
			consumer.accept(chunk);
			visited++;
			if (System.nanoTime() >= deadline) {
				break;
			}
		}
		return visited;
	}

	/** Drops queued keys whose chunk is no longer loaded. */
	public void retainLoaded(ClientLevel level) {
		if (level == null) {
			clear();
			return;
		}
		Iterator<Map.Entry<Long, Long>> iterator = readyAt.entrySet().iterator();
		while (iterator.hasNext()) {
			long key = iterator.next().getKey();
			if (level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key)) == null) {
				iterator.remove();
				queuedAt.remove(key);
			}
		}
	}
}
