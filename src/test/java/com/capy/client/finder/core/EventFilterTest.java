package com.capy.client.finder.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EventFilterTest {
	private static final SoundKinds.Kind MINING = SoundKinds.Kind.MINING;
	private static final SoundKinds.Kind BUILDING = SoundKinds.Kind.BUILDING;
	private static final SoundKinds.Kind STORAGE = SoundKinds.Kind.STORAGE;
	private static final SoundKinds.Kind REDSTONE = SoundKinds.Kind.REDSTONE;
	private static final SoundKinds.Kind MACHINE = SoundKinds.Kind.MACHINE;
	private static final SoundKinds.Kind EXPLOSION = SoundKinds.Kind.EXPLOSION;

	@Test
	void fallingBlocksDropNearbyMiningAndBuilding() {
		EventFilter filter = new EventFilter();
		filter.noteFallingBlock(0, 64, 0, 10_000);
		assertEquals(0.0, filter.judge(MINING, 2, 64, 2, 11_000), 1e-9);
		assertEquals(0.0, filter.judge(BUILDING, 0, 66, 0, 12_000), 1e-9);

		assertEquals(0.0, filter.judge(MINING, 10, 64, 0, 13_000), 1e-9);
		assertEquals(0.0, filter.judge(MINING, 10, 64, 0, 14_000), 1e-9);
		assertEquals(1.0, filter.judge(MINING, 10, 64, 0, 15_000), 1e-9);
	}

	@Test
	void miningAndBuildingNeedThreeRepeats() {
		EventFilter filter = new EventFilter();
		assertEquals(0.0, filter.judge(MINING, 0, 0, 0, 1_000), 1e-9);
		assertEquals(0.0, filter.judge(MINING, 2, 0, 0, 6_000), 1e-9);
		assertEquals(1.0, filter.judge(MINING, 4, 0, 0, 11_000), 1e-9);

		assertEquals(0.0, filter.judge(BUILDING, 0, 0, 0, 1_000), 1e-9);
		assertEquals(0.0, filter.judge(BUILDING, 2, 0, 0, 6_000), 1e-9);
		assertEquals(1.0, filter.judge(BUILDING, 4, 0, 0, 11_000), 1e-9);

		// The three old mining events have aged out of the 20 s window.
		assertEquals(0.0, filter.judge(MINING, 4, 0, 0, 31_500), 1e-9);
	}

	@Test
	void explosionsNeedPrimedTntNearbyAndRecent() {
		EventFilter filter = new EventFilter();
		assertEquals(0.0, filter.judge(EXPLOSION, 0, 0, 0, 1_000), 1e-9);

		filter.noteTnt(10, 0, 0, 2_000);
		assertEquals(1.0, filter.judge(EXPLOSION, 0, 0, 0, 3_000), 1e-9);

		// 12 s after priming the memory has expired.
		assertEquals(0.0, filter.judge(EXPLOSION, 0, 0, 0, 14_001), 1e-9);

		filter.noteTnt(0, 0, 100, 20_000);
		assertEquals(0.0, filter.judge(EXPLOSION, 0, 0, 0, 21_000), 1e-9);
	}

	@Test
	void storageNeedsRepeatsOrTwoDifferentBlocks() {
		EventFilter single = new EventFilter();
		assertEquals(0.4, single.judge(STORAGE, 0, 0, 0, 1_000), 1e-9);
		assertEquals(1.0, single.judge(STORAGE, 5, 0, 0, 2_000), 1e-9);

		EventFilter repeated = new EventFilter();
		assertEquals(0.4, repeated.judge(STORAGE, 0, 0, 0, 1_000), 1e-9);
		assertEquals(0.4, repeated.judge(STORAGE, 0, 0, 0, 2_000), 1e-9);
		assertEquals(1.0, repeated.judge(STORAGE, 0, 0, 0, 3_000), 1e-9);

		EventFilter far = new EventFilter();
		assertEquals(0.4, far.judge(STORAGE, 0, 0, 0, 1_000), 1e-9);
		assertEquals(0.4, far.judge(STORAGE, 20, 0, 0, 2_000), 1e-9);
	}

	@Test
	void redstoneNeedsTwoNearbyEvents() {
		EventFilter filter = new EventFilter();
		assertEquals(0.5, filter.judge(REDSTONE, 0, 0, 0, 1_000), 1e-9);
		assertEquals(1.0, filter.judge(REDSTONE, 2, 0, 0, 2_000), 1e-9);

		EventFilter far = new EventFilter();
		assertEquals(0.5, far.judge(REDSTONE, 0, 0, 0, 1_000), 1e-9);
		assertEquals(0.5, far.judge(REDSTONE, 9, 0, 0, 2_000), 1e-9);

		// Both prior events are older than the 20 s window.
		assertEquals(0.5, filter.judge(REDSTONE, 2, 0, 0, 25_000), 1e-9);
	}

	@Test
	void structuresDiscountStorageRedstoneAndMachines() {
		EventFilter filter = new EventFilter();
		filter.noteStructure(0, 64, 0, 1_000);
		assertEquals(0.1, filter.judge(STORAGE, 10, 64, 10, 2_000), 1e-9);
		assertEquals(0.125, filter.judge(REDSTONE, 10, 64, 10, 3_000), 1e-9);
		assertEquals(0.25, filter.judge(MACHINE, 10, 64, 10, 4_000), 1e-9);

		// Ten minutes after the structure sound the discount is gone.
		assertEquals(1.0, filter.judge(MACHINE, 10, 64, 10, 601_001), 1e-9);
	}

	@Test
	void structureDiscountIsLocal() {
		EventFilter filter = new EventFilter();
		filter.noteStructure(0, 64, 0, 1_000);
		assertEquals(1.0, filter.judge(MACHINE, 0, 100, 0, 2_000), 1e-9);
		assertEquals(1.0, filter.judge(MACHINE, 49, 64, 0, 3_000), 1e-9);

		EventFilter vertical = new EventFilter();
		vertical.noteStructure(0, 64, 0, 1_000);
		assertEquals(0.25, vertical.judge(MACHINE, 0, 88, 0, 2_000), 1e-9);
	}

	@Test
	void clearDropsAllMemory() {
		EventFilter filter = new EventFilter();
		filter.judge(MINING, 0, 0, 0, 1_000);
		filter.judge(MINING, 0, 0, 0, 2_000);
		assertEquals(1.0, filter.judge(MINING, 0, 0, 0, 3_000), 1e-9);
		filter.clear();
		assertEquals(0.0, filter.judge(MINING, 0, 0, 0, 4_000), 1e-9);
	}
}
