package com.capy.client.antivanish.core;

import java.util.List;
import java.util.Set;

/**
 * The pure request/response half of the optional probe.
 *
 * <p>The planner decides when and whom to ask. This runner enforces the other
 * safety rule: only one tab-complete request may be in flight. A request that
 * gets no answer is dropped after {@link #TIMEOUT_MS}; a request that is off
 * sends nothing and forgets any pending answer.</p>
 */
public final class ProbeRunner {
	public static final long TIMEOUT_MS = 15_000L;

	@FunctionalInterface
	public interface Transport {
		/** Requests one suggestion answer and returns its id, or -1 when unavailable. */
		int request(String command);
	}

	private final ProbePlanner planner;
	private final Transport transport;
	private boolean pending;
	private int pendingId = -1;
	private String pendingName;
	private long pendingAt;

	public ProbeRunner(ProbePlanner planner, Transport transport) {
		this.planner = planner;
		this.transport = transport;
	}

	public void setNames(List<String> names) {
		planner.setNames(names == null ? List.of() : names);
	}

	public void tick(boolean enabled, long now, Set<String> online) {
		if (!enabled) {
			clearPending();
			return;
		}
		if (pending && now - pendingAt > TIMEOUT_MS) {
			clearPending();
		}
		if (pending) {
			return;
		}
		String name = planner.next(now, online == null ? Set.of() : online);
		if (name == null) {
			return;
		}
		int id = transport.request("msg " + name);
		if (id >= 0) {
			pending = true;
			pendingId = id;
			pendingName = name;
			pendingAt = now;
		}
	}

	/**
	 * Accepts one server answer. The exact watched name must be present, not
	 * just a prefix, and the answer must belong to the request in flight.
	 *
	 * @return the watched name to report as hidden-but-targetable, or null
	 */
	public String acceptResponse(int id, List<String> suggestions, long now) {
		if (!pending || id != pendingId) {
			return null;
		}
		if (now - pendingAt > TIMEOUT_MS) {
			clearPending();
			return null;
		}
		String name = pendingName;
		clearPending();
		if (suggestions == null) {
			return null;
		}
		for (String suggestion : suggestions) {
			if (suggestion != null && suggestion.equalsIgnoreCase(name)) {
				return name;
			}
		}
		return null;
	}

	public void rateLimited(long now) {
		planner.rateLimited(now);
	}

	public void reset() {
		planner.reset();
		clearPending();
	}

	public boolean pending() {
		return pending;
	}

	private void clearPending() {
		pending = false;
		pendingId = -1;
		pendingName = null;
		pendingAt = 0L;
	}
}
