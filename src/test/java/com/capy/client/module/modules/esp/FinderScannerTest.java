package com.capy.client.module.modules.esp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.capy.client.finder.core.Chunks;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.junit.jupiter.api.Test;

class FinderScannerTest {
	@Test
	void registeringQueuesLoadedChunksWithoutAToggle() {
		FakeView view = new FakeView(List.of(
				Chunks.key(0, 0),
				Chunks.key(1, 0),
				Chunks.key(0, 1),
				Chunks.key(-1, 0),
				Chunks.key(0, -1)));
		FinderScanner scanner = new FinderScanner(view);
		CountingConsumer consumer = new CountingConsumer();

		scanner.register(consumer);
		assertEquals(5, scanner.pendingCount());

		for (int tick = 0; tick < 10 && consumer.scans < 5; tick++) {
			scanner.tick();
		}

		assertEquals(5, consumer.scans);
		assertEquals(0, scanner.pendingCount());
	}

	@Test
	void unregisteringTheLastConsumerEmptiesTheQueue() {
		FakeView view = new FakeView(List.of(
				Chunks.key(0, 0),
				Chunks.key(1, 0),
				Chunks.key(2, 0),
				Chunks.key(3, 0),
				Chunks.key(4, 0)));
		FinderScanner scanner = new FinderScanner(view);
		CountingConsumer consumer = new CountingConsumer();

		scanner.register(consumer);
		assertEquals(5, scanner.pendingCount());

		scanner.unregister(consumer);
		assertEquals(0, scanner.pendingCount());

		scanner.tick();
		assertEquals(0, consumer.scans);
	}

	@Test
	void anUnfinishedConsumerResumesOnTheNextTick() {
		FakeView view = new FakeView(List.of(Chunks.key(0, 0)));
		FinderScanner scanner = new FinderScanner(view);
		SlowConsumer consumer = new SlowConsumer();

		scanner.register(consumer);
		scanner.tick();

		assertEquals(1, consumer.calls);
		assertEquals(0, scanner.pendingCount());

		scanner.tick();
		assertEquals(2, consumer.calls);
		assertEquals(0, scanner.pendingCount());
	}

	private static final class FakeView implements FinderScanner.View {
		private final List<Long> loaded;

		private FakeView(List<Long> loaded) {
			this.loaded = loaded;
		}

		@Override
		public boolean ready() {
			return true;
		}

		@Override
		public ClientLevel level() {
			return null;
		}

		@Override
		public int playerChunkX() {
			return 0;
		}

		@Override
		public int playerChunkZ() {
			return 0;
		}

		@Override
		public List<Long> loadedChunkKeys() {
			return loaded;
		}

		@Override
		public boolean isLoaded(long key) {
			return loaded.contains(key);
		}

		@Override
		public boolean scan(FinderScanner.Consumer consumer, ClientLevel level, long key, long deadlineNanos) {
			return consumer.scan(level, null, deadlineNanos);
		}
	}

	private static final class CountingConsumer implements FinderScanner.Consumer {
		private int scans;

		@Override
		public String name() {
			return "counting";
		}

		@Override
		public boolean scan(ClientLevel level, LevelChunk chunk, long deadlineNanos) {
			scans++;
			return true;
		}

		@Override
		public void unloaded(long chunkKey) {
		}

		@Override
		public void reset() {
		}
	}

	private static final class SlowConsumer implements FinderScanner.Consumer {
		private int calls;

		@Override
		public String name() {
			return "slow";
		}

		@Override
		public boolean scan(ClientLevel level, LevelChunk chunk, long deadlineNanos) {
			calls++;
			return calls >= 2;
		}

		@Override
		public void unloaded(long chunkKey) {
		}

		@Override
		public void reset() {
		}
	}
}
