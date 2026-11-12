package com.capy.client.module.modules.esp;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A resumable, time-budgeted slice queue.
 *
 * <p>The scheduler owns only keys and slice cursors; the module owns the chunk
 * lookup and the scan state. That keeps the budget testable without a running
 * Minecraft client. One call to {@link #drain} performs at most one slice per
 * job before checking the clock, so a slow slice cannot delay the next tick by
 * an unbounded amount of work.</p>
 */
public final class ScanScheduler {
	public enum Result { CONTINUE, DONE, DROP }

	@FunctionalInterface
	public interface SliceScan {
		Result scan(long key, int slice);
	}

	private static final class Job {
		long readyAt;
		int slice;
		boolean dirty;
	}

	private final Map<Long, Job> jobs = new LinkedHashMap<>();

	public void offer(long key, long nowTick, long settleTicks) {
		Job job = jobs.get(key);
		if (job != null) {
			if (job.slice > 0) {
				job.dirty = true;
			}
			return;
		}
		job = new Job();
		job.readyAt = nowTick + Math.max(0, settleTicks);
		jobs.put(key, job);
	}

	public void markDirty(long key, long nowTick) {
		offer(key, nowTick, 2L);
	}

	public void remove(long key) {
		jobs.remove(key);
	}

	public void clear() {
		jobs.clear();
	}

	public int size() {
		return jobs.size();
	}

	/**
	 * Runs at most one slice per ready job and stops at the wall-clock deadline
	 * or the slice cap. Returns the number of slices visited.
	 */
	public int drain(long nowTick, long budgetNanos, int maxSlices, SliceScan scanner) {
		long deadline = System.nanoTime() + Math.max(0L, budgetNanos);
		int visited = 0;
		Iterator<Map.Entry<Long, Job>> iterator = jobs.entrySet().iterator();
		while (iterator.hasNext() && visited < Math.max(1, maxSlices)) {
			Map.Entry<Long, Job> entry = iterator.next();
			Job job = entry.getValue();
			if (job.readyAt > nowTick) {
				continue;
			}
			Result result = scanner.scan(entry.getKey(), job.slice);
			visited++;
			switch (result) {
				case DROP -> iterator.remove();
				case DONE -> {
					if (job.dirty) {
						job.dirty = false;
						job.slice = 0;
						job.readyAt = nowTick + 2L;
					} else {
						iterator.remove();
					}
				}
				case CONTINUE -> job.slice++;
			}
			if (System.nanoTime() >= deadline) {
				break;
			}
		}
		return visited;
	}
}
