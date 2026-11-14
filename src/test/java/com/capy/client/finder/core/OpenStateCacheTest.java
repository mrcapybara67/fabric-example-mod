package com.capy.client.finder.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OpenStateCacheTest {
	@Test
	void cachedAnswersMatchTheUncachedPath() {
		OpenStateCache<Object> cache = new OpenStateCache<>();
		Object stone = new Object();
		Object air = new Object();
		Object fence = new Object();
		Map<Object, Boolean> shapes = new IdentityHashMap<>();
		shapes.put(stone, false);
		shapes.put(air, true);
		shapes.put(fence, false);

		for (Object state : List.of(stone, air, fence)) {
			boolean expected = shapes.get(state);
			assertEquals(expected, cache.isOpen(state, false, () -> shapes.get(state)));
		}
		for (Object state : List.of(stone, air, fence)) {
			boolean expected = shapes.get(state);
			assertEquals(expected, cache.isOpen(state, false, () -> {
				throw new AssertionError("cached state was recomputed");
			}));
		}
		assertEquals(3, cache.size());
	}

	@Test
	void positionDependentStatesAreNeverCached() {
		OpenStateCache<Object> cache = new OpenStateCache<>();
		Object fence = new Object();
		AtomicInteger calls = new AtomicInteger();

		assertFalse(cache.isOpen(fence, true, () -> {
			calls.incrementAndGet();
			return false;
		}));
		assertFalse(cache.isOpen(fence, true, () -> {
			calls.incrementAndGet();
			return false;
		}));

		assertEquals(2, calls.get());
		assertEquals(0, cache.size());
	}
}
