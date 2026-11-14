package com.capy.client.finder.core;

import java.util.IdentityHashMap;
import java.util.function.BooleanSupplier;

/**
 * Per-block-state cache for "does this state have an empty collision shape".
 *
 * <p>The key is compared by identity, not equality: block states are interned
 * registry objects, so an {@link IdentityHashMap} avoids walking the property
 * map for every cell. Only states whose collision shape does not depend on
 * their position may be cached; the caller passes {@code positionDependent}
 * for the few blocks that inspect their neighbours (fences, walls, panes and
 * chests) so those are always evaluated at their real position.</p>
 */
public final class OpenStateCache<T> {
	private final IdentityHashMap<T, Boolean> cache = new IdentityHashMap<>();

	/**
	 * Returns the cached answer for a position-independent state, or computes
	 * it once. A position-dependent state is always computed and never stored.
	 */
	public boolean isOpen(T state, boolean positionDependent, BooleanSupplier compute) {
		if (positionDependent) {
			return compute.getAsBoolean();
		}
		Boolean known = cache.get(state);
		if (known == null) {
			known = compute.getAsBoolean();
			cache.put(state, known);
		}
		return known.booleanValue();
	}

	/** Number of states currently cached, for tests and diagnostics. */
	public int size() {
		return cache.size();
	}

	public void clear() {
		cache.clear();
	}
}
