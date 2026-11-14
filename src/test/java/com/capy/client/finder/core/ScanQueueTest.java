package com.capy.client.finder.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ScanQueueTest {
	@Test
	void duplicateOffersKeepOneEntry() {
		ScanQueue queue = new ScanQueue();
		long key = Chunks.key(4, -7);

		assertTrue(queue.offer(key));
		assertFalse(queue.offer(key));
		assertEquals(1, queue.size());
		assertTrue(queue.contains(key));
	}

	@Test
	void offerAllSkipsDuplicates() {
		ScanQueue queue = new ScanQueue();
		queue.offer(Chunks.key(0, 0));
		queue.offerAll(List.of(Chunks.key(0, 0), Chunks.key(1, 0), Chunks.key(1, 0)));

		assertEquals(2, queue.size());
	}

	@Test
	void pollReturnsTheNearestChunkFirst() {
		ScanQueue queue = new ScanQueue();
		long far = Chunks.key(5, 5);
		long near = Chunks.key(1, 1);
		long middle = Chunks.key(-2, 0);
		queue.offerAll(List.of(far, near, middle));

		assertEquals(near, queue.pollNearest(0, 0));
		assertEquals(middle, queue.pollNearest(0, 0));
		assertEquals(far, queue.pollNearest(0, 0));
		assertNull(queue.pollNearest(0, 0));
	}

	@Test
	void aPolledChunkCanBeQueuedAgain() {
		ScanQueue queue = new ScanQueue();
		long key = Chunks.key(8, 8);
		queue.offer(key);

		assertEquals(key, queue.pollNearest(0, 0));
		assertFalse(queue.contains(key));
		assertTrue(queue.offer(key));
		assertTrue(queue.contains(key));
		assertEquals(key, queue.pollNearest(0, 0));
	}

	@Test
	void removeAndClearDropPendingChunks() {
		ScanQueue queue = new ScanQueue();
		long first = Chunks.key(0, 0);
		long second = Chunks.key(1, 0);
		queue.offerAll(List.of(first, second));

		queue.remove(first);
		assertFalse(queue.contains(first));
		assertEquals(1, queue.size());

		queue.clear();
		assertEquals(0, queue.size());
	}
}
