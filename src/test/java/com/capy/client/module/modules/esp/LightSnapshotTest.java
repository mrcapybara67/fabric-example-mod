package com.capy.client.module.modules.esp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class LightSnapshotTest {
	@Test
	void sectionZeroIsOneBelowMinimumSection() {
		LightSignalQueue.Snapshot snapshot = LightSignalQueue.newSnapshot();
		BitSet mask = new BitSet();
		mask.set(1);
		assertTrue(LightSignalQueue.apply(snapshot, false, -64, 4, mask,
				List.of(new byte[2048]), new BitSet(), true));
	}

	@Test
	void unchangedRepeatDoesNotFlag() {
		LightSignalQueue.Snapshot snapshot = LightSignalQueue.newSnapshot();
		BitSet mask = new BitSet();
		mask.set(0);
		byte[] full = new byte[2048];
		java.util.Arrays.fill(full, (byte) 0xFF);
		assertFalse(LightSignalQueue.apply(snapshot, true, 0, 4, mask,
				List.of(full), new BitSet(), false));
		assertFalse(LightSignalQueue.apply(snapshot, true, 0, 4, mask,
				List.of(full), new BitSet(), false));
	}

	@Test
	void changedSectionFlags() {
		LightSignalQueue.Snapshot snapshot = LightSignalQueue.newSnapshot();
		BitSet mask = new BitSet();
		mask.set(0);
		byte[] normal = new byte[2048];
		normal[0] = 1;
		assertFalse(LightSignalQueue.apply(snapshot, false, 0, 4, mask,
				List.of(normal), new BitSet(), false));
		assertTrue(LightSignalQueue.apply(snapshot, false, 0, 4, mask,
				List.of(new byte[2048]), new BitSet(), false));
	}

	@Test
	void classifierStillSeparatesKinds() {
		assertTrue(LightSignalQueue.classify(new byte[2048]) == LightSignalQueue.Kind.ZEROED_PRESENT);
		byte[] mixed = new byte[2048];
		mixed[0] = 1;
		assertTrue(LightSignalQueue.classify(mixed) == LightSignalQueue.Kind.NORMAL);
	}
}
