package com.capy.client.module.modules.esp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ScanSchedulerTest {
	@Test
	void unchangedChunkIsNotRescanned() {
		ScanScheduler scheduler = new ScanScheduler();
		AtomicInteger scans = new AtomicInteger();
		scheduler.offer(HeatMap.key(0, 0), 0, 0);
		scheduler.drain(10, 3_000_000L, 4, (key, slice) -> {
			scans.incrementAndGet();
			return ScanScheduler.Result.DONE;
		});
		scheduler.drain(11, 3_000_000L, 4, (key, slice) -> {
			scans.incrementAndGet();
			return ScanScheduler.Result.DONE;
		});
		assertEquals(1, scans.get());
	}

	@Test
	void dirtyChunkIsRescannedOnce() {
		ScanScheduler scheduler = new ScanScheduler();
		AtomicInteger scans = new AtomicInteger();
		scheduler.offer(7L, 0, 0);
		scheduler.drain(10, 3_000_000L, 4, (key, slice) -> {
			scans.incrementAndGet();
			return ScanScheduler.Result.DONE;
		});
		scheduler.markDirty(7L, 10);
		scheduler.markDirty(7L, 10);
		scheduler.drain(13, 3_000_000L, 4, (key, slice) -> {
			scans.incrementAndGet();
			return ScanScheduler.Result.DONE;
		});
		assertEquals(2, scans.get());
	}

	@Test
	void slowSliceStopsAtTheBudget() {
		ScanScheduler scheduler = new ScanScheduler();
		AtomicInteger slices = new AtomicInteger();
		scheduler.offer(1L, 0, 0);
		scheduler.offer(2L, 0, 0);
		scheduler.drain(10, 1_000L, 4, (key, slice) -> {
			slices.incrementAndGet();
			long until = System.nanoTime() + 2_000_000L;
			while (System.nanoTime() < until) {
				Thread.onSpinWait();
			}
			return ScanScheduler.Result.CONTINUE;
		});
		assertEquals(1, slices.get());
	}
}
