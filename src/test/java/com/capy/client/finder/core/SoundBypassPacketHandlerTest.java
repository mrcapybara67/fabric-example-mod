package com.capy.client.finder.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SoundBypassPacketHandlerTest {
	private SoundBypassFeed feed;

	@BeforeEach
	void setUp() {
		feed = SoundBypassFeed.get();
		feed.release();
		feed.acquire();
		feed.refreshSnapshot(100, 64, 100, new double[0]);
	}

	@AfterEach
	void tearDown() {
		feed.release();
	}

	@Test
	void eventWithinSixBlocksOfMeIsIgnored() {
		feed.refreshSnapshot(0, 64, 0, new double[0]);
		feed.onBlockEvent(SoundKinds.Kind.STORAGE, 3.0, 5, 64, 0, 1_000);

		assertTrue(feed.top(1_000, 0, 10).isEmpty());
		assertEquals(0, feed.stats().total(ChannelStats.Channel.BLOCK_EVENT));
	}

	@Test
	void eventWithinThreeBlocksOfVisibleEntityIsIgnored() {
		feed.refreshSnapshot(0, 64, 0, new double[]{10, 64, 0});
		feed.onBlockEvent(SoundKinds.Kind.STORAGE, 3.0, 11, 64, 0, 1_000);
		assertTrue(feed.top(1_000, 0, 10).isEmpty());

		feed.onBlockEvent(SoundKinds.Kind.STORAGE, 3.0, 14, 64, 0, 2_000);
		assertEquals(1, feed.top(2_000, 0, 10).size());
	}

	@Test
	void sandPlaceNextToAFallingBlockIsIgnored() {
		feed.onFallingBlock(0, 64, 0, 1_000);
		feed.onSound("block.sand.place", 1, 64, 1, 1_100);
		assertTrue(feed.top(1_100, 0, 10).isEmpty());

		// Gravity-block sounds are already IGNORE; this also proves the falling
		// block memory drops a normal building sound next to the fall.
		feed.onBlockEvent(SoundKinds.Kind.BUILDING, 1.0, 1, 64, 1, 1_200);
		assertTrue(feed.top(1_200, 0, 10).isEmpty());
		assertEquals(0, feed.stats().total(ChannelStats.Channel.BLOCK_EVENT));
	}

	@Test
	void trialSpawnerDiscountsNearbyPiston() {
		feed.onSound("block.trial_spawner.ambient", 0.5, 64, 0.5, 1_000);
		feed.onSound("block.piston.extend", 0.5, 64, 0.5, 1_100);

		List<ActivityGrid.Cell> cells = feed.top(1_100, 0, 10);
		assertEquals(1, cells.size());
		assertEquals(0.25, cells.getFirst().score(), 1e-9);
		assertEquals(SoundKinds.Kind.REDSTONE, cells.getFirst().dominant());
	}

	@Test
	void chestSoundAndChestBlockEventAtTheSameBlockCountOnce() {
		feed.onSound("block.chest.open", 0.5, 64.5, 0.5, 1_000);
		feed.onBlockEvent(SoundKinds.Kind.STORAGE, 3.0, 0.5, 64.5, 0.5, 1_050);

		List<ActivityGrid.Cell> cells = feed.top(1_050, 0, 10);
		assertEquals(1, cells.size());
		// Only one event was accepted; the score has decayed for 50 ms.
		double expected = 1.2 * Math.pow(0.5, 50.0 / ActivityGrid.HALF_LIFE_MS);
		assertEquals(expected, cells.getFirst().score(), 1e-9);
	}
}
