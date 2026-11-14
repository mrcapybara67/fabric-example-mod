package com.capy.client.module.modules.esp;

import com.capy.client.finder.core.Chunks;
import com.capy.client.finder.core.ScanQueue;
import com.capy.client.util.Guard;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * One shared, read-only chunk scanner for every finder.
 *
 * <p>The scanner owns the only pending queue. A finder registers once when it
 * is enabled and receives loaded chunks on join, on chunk loads, on block and
 * light updates, and on a slow safety-net pass. Scans are time-sliced: an
 * unfinished consumer keeps the same chunk next tick, while finished consumers
 * move on. Nothing is sent to the server and no world state is changed.</p>
 */
public final class FinderScanner {
	public interface Consumer {
		String name();

		boolean scan(ClientLevel level, LevelChunk chunk, long deadlineNanos);

		void unloaded(long chunkKey);

		void reset();
	}

	/** Test seam: the production view reads the current client world. */
	interface View {
		boolean ready();

		ClientLevel level();

		int playerChunkX();

		int playerChunkZ();

		List<Long> loadedChunkKeys();

		boolean isLoaded(long key);

		boolean scan(Consumer consumer, ClientLevel level, long key, long deadlineNanos);
	}

	private static final long TICK_BUDGET_NANOS = 2_000_000L;
	private static final long UPDATE_DEBOUNCE_TICKS = 20L;
	private static final int SAFETY_RESCAN_TICKS = 6000;
	private static final int PRUNE_EVERY_TICKS = 200;
	private static final FinderScanner INSTANCE = new FinderScanner();

	private final View view;
	private final ScanQueue queue = new ScanQueue();
	private final List<Consumer> consumers = new CopyOnWriteArrayList<>();
	private final Set<Consumer> unfinished = new HashSet<>();
	private final Map<Long, Long> recentUpdates = new HashMap<>();
	private final Map<Consumer, Integer> scanned = new HashMap<>();
	private boolean installed;
	private boolean active;
	private long activeKey;
	private long ticks;
	private int sinceSafety;

	private FinderScanner() {
		this(new MinecraftView());
	}

	FinderScanner(View view) {
		this.view = view;
	}

	public static FinderScanner get() {
		return INSTANCE;
	}

	/** Installs the Fabric chunk events once. */
	public void install() {
		if (installed) {
			return;
		}
		installed = true;
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) ->
			Guard.run("finder chunk load", () -> onChunkLoad(level, chunk)));
		ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) ->
			Guard.run("finder chunk unload", () -> onChunkUnload(level, chunk)));
	}

	public void register(Consumer consumer) {
		if (consumer == null || consumers.contains(consumer)) {
			return;
		}
		consumers.add(consumer);
		scanned.putIfAbsent(consumer, 0);
		queueAllLoaded();
	}

	public void unregister(Consumer consumer) {
		if (consumer == null || !consumers.remove(consumer)) {
			return;
		}
		unfinished.remove(consumer);
		scanned.remove(consumer);
		if (consumers.isEmpty()) {
			queue.clear();
			recentUpdates.clear();
			unfinished.clear();
			scanned.clear();
			active = false;
		} else if (active && unfinished.isEmpty()) {
			active = false;
		}
	}

	/** Queues every loaded chunk within the view distance plus one. */
	public void queueAllLoaded() {
		if (consumers.isEmpty() || !view.ready()) {
			return;
		}
		queue.offerAll(view.loadedChunkKeys());
	}

	/** Requeues one chunk, used by a consumer that must wait before scanning. */
	public void requeue(long key) {
		if (!consumers.isEmpty()) {
			queue.offer(key);
		}
	}

	public void onChunkLoad(ClientLevel level, LevelChunk chunk) {
		if (consumers.isEmpty() || level != view.level()) {
			return;
		}
		int cx = chunk.getPos().x;
		int cz = chunk.getPos().z;
		long key = Chunks.key(cx, cz);
		queue.offer(key);
		queue.offer(Chunks.key(cx + 1, cz));
		queue.offer(Chunks.key(cx - 1, cz));
		queue.offer(Chunks.key(cx, cz + 1));
		queue.offer(Chunks.key(cx, cz - 1));
	}

	public void onChunkUnload(ClientLevel level, LevelChunk chunk) {
		if (consumers.isEmpty() || level != view.level()) {
			return;
		}
		long key = Chunks.key(chunk.getPos().x, chunk.getPos().z);
		queue.remove(key);
		recentUpdates.remove(key);
		if (active && activeKey == key) {
			active = false;
			unfinished.clear();
		}
		for (Consumer consumer : consumers) {
			Guard.run("finder unload " + consumer.name(), () -> consumer.unloaded(key));
		}
	}

	public void onBlockUpdate(BlockPos pos) {
		if (consumers.isEmpty() || pos == null) {
			return;
		}
		queueUpdate(Chunks.key(pos.getX() >> 4, pos.getZ() >> 4));
	}

	public void onSectionBlocksUpdate(ClientboundSectionBlocksUpdatePacket packet) {
		if (consumers.isEmpty() || packet == null) {
			return;
		}
		boolean[] queued = {false};
		packet.runUpdates((pos, state) -> {
			if (!queued[0]) {
				queued[0] = true;
				queueUpdate(Chunks.key(pos.getX() >> 4, pos.getZ() >> 4));
			}
		});
	}

	public void onChunkData(int cx, int cz) {
		if (!consumers.isEmpty()) {
			queue.offer(Chunks.key(cx, cz));
		}
	}

	public void onLightUpdate(int cx, int cz) {
		if (consumers.isEmpty()) {
			return;
		}
		queueUpdate(Chunks.key(cx, cz));
	}

	public void onWorldReset() {
		queue.clear();
		recentUpdates.clear();
		unfinished.clear();
		scanned.clear();
		active = false;
		ticks = 0L;
		sinceSafety = 0;
		for (Consumer consumer : consumers) {
			Guard.run("finder reset " + consumer.name(), consumer::reset);
		}
	}

	/** Runs at most two milliseconds of scanning for this client tick. */
	public void tick() {
		if (consumers.isEmpty()) {
			return;
		}
		if (!view.ready()) {
			return;
		}
		ticks++;
		sinceSafety++;
		if (sinceSafety >= SAFETY_RESCAN_TICKS) {
			sinceSafety = 0;
			queueAllLoaded();
		}
		if (ticks % PRUNE_EVERY_TICKS == 0L) {
			recentUpdates.entrySet().removeIf(entry -> ticks - entry.getValue() >= UPDATE_DEBOUNCE_TICKS);
		}

		long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
		// Always make one attempt even if the tiny budget elapsed while the
		// frame was being scheduled, otherwise a loaded chunk could starve
		// forever on a busy client.
		boolean attempted = false;
		while (!attempted || System.nanoTime() < deadline) {
			if (!active) {
				Long key = queue.pollNearest(view.playerChunkX(), view.playerChunkZ());
				if (key == null) {
					return;
				}
				if (!view.isLoaded(key)) {
					continue;
				}
				active = true;
				activeKey = key;
				unfinished.clear();
				unfinished.addAll(consumers);
				if (unfinished.isEmpty()) {
					active = false;
					return;
				}
			}

			if (!view.isLoaded(activeKey)) {
				active = false;
				unfinished.clear();
				continue;
			}
			ClientLevel level = view.level();
			for (Consumer consumer : List.copyOf(unfinished)) {
				if (attempted && System.nanoTime() >= deadline) {
					return;
				}
				if (!consumers.contains(consumer)) {
					unfinished.remove(consumer);
					continue;
				}
				attempted = true;
				boolean done;
				try {
					done = view.scan(consumer, level, activeKey, deadline);
				} catch (Throwable failure) {
					Guard.report("finder scan " + consumer.name(), failure);
					done = true;
				}
				if (done) {
					unfinished.remove(consumer);
					scanned.merge(consumer, 1, Integer::sum);
				} else {
					// A consumer only returns false when it used its slice. Keep the
					// same chunk active and pick it up on the next client tick.
					return;
				}
			}
			if (unfinished.isEmpty()) {
				active = false;
			}
			if (System.nanoTime() >= deadline) {
				return;
			}
		}
	}

	int pendingCount() {
		return queue.size();
	}

	public int queueSize() {
		return queue.size();
	}

	public int scannedCount(Consumer consumer) {
		return scanned.getOrDefault(consumer, 0);
	}

	private void queueUpdate(long key) {
		long last = recentUpdates.getOrDefault(key, Long.MIN_VALUE);
		if (last != Long.MIN_VALUE && ticks - last < UPDATE_DEBOUNCE_TICKS) {
			return;
		}
		recentUpdates.put(key, ticks);
		queue.offer(key);
	}

	private static final class MinecraftView implements View {
		@Override
		public boolean ready() {
			Minecraft mc = Minecraft.getInstance();
			return mc != null && mc.player != null && mc.level != null;
		}

		@Override
		public ClientLevel level() {
			Minecraft mc = Minecraft.getInstance();
			return mc == null ? null : mc.level;
		}

		@Override
		public int playerChunkX() {
			Minecraft mc = Minecraft.getInstance();
			return mc == null || mc.player == null ? 0 : mc.player.blockPosition().getX() >> 4;
		}

		@Override
		public int playerChunkZ() {
			Minecraft mc = Minecraft.getInstance();
			return mc == null || mc.player == null ? 0 : mc.player.blockPosition().getZ() >> 4;
		}

		@Override
		public List<Long> loadedChunkKeys() {
			Minecraft mc = Minecraft.getInstance();
			if (mc == null || mc.level == null || mc.player == null) {
				return List.of();
			}
			int radius = Math.max(0, mc.options.getEffectiveRenderDistance()) + 1;
			int cx = mc.player.blockPosition().getX() >> 4;
			int cz = mc.player.blockPosition().getZ() >> 4;
			List<Long> keys = new ArrayList<>();
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dx = -radius; dx <= radius; dx++) {
					int x = cx + dx;
					int z = cz + dz;
					if (mc.level.getChunkSource().getChunkNow(x, z) != null) {
						keys.add(Chunks.key(x, z));
					}
				}
			}
			return keys;
		}

		@Override
		public boolean isLoaded(long key) {
			return chunk(key) != null;
		}

		@Override
		public boolean scan(Consumer consumer, ClientLevel level, long key, long deadlineNanos) {
			LevelChunk chunk = chunk(key);
			if (chunk == null) {
				return true;
			}
			return consumer.scan(level, chunk, deadlineNanos);
		}

		private LevelChunk chunk(long key) {
			Minecraft mc = Minecraft.getInstance();
			if (mc == null || mc.level == null) {
				return null;
			}
			return mc.level.getChunkSource().getChunkNow(Chunks.x(key), Chunks.z(key));
		}
	}
}
