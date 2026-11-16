package com.capy.client.finder.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChannelStatsTest {
	@Test
	void bandsUseTheDocumentedEdges() {
		assertEquals(ChannelStats.Band.ABOVE, ChannelStats.band(8.01, 0));
		assertEquals(ChannelStats.Band.NEAR, ChannelStats.band(8.0, 0));
		assertEquals(ChannelStats.Band.NEAR, ChannelStats.band(0, 0));
		assertEquals(ChannelStats.Band.NEAR, ChannelStats.band(-8.0, 0));
		assertEquals(ChannelStats.Band.MID, ChannelStats.band(-8.01, 0));
		assertEquals(ChannelStats.Band.MID, ChannelStats.band(-32.0, 0));
		assertEquals(ChannelStats.Band.DEEP, ChannelStats.band(-32.01, 0));
	}

	@Test
	void silentDeepNeedsFiftyShallowEventsAndNoDeepOnes() {
		ChannelStats stats = new ChannelStats();
		for (int i = 0; i < 49; i++) {
			stats.count(ChannelStats.Channel.BLOCK_EVENT, 20, 0);
		}
		assertFalse(stats.silentDeep(ChannelStats.Channel.BLOCK_EVENT));
		stats.count(ChannelStats.Channel.BLOCK_EVENT, 20, 0);
		assertEquals(50, stats.total(ChannelStats.Channel.BLOCK_EVENT));
		assertTrue(stats.silentDeep(ChannelStats.Channel.BLOCK_EVENT));

		stats.count(ChannelStats.Channel.BLOCK_EVENT, -40, 0);
		assertEquals(1, stats.deep(ChannelStats.Channel.BLOCK_EVENT));
		assertFalse(stats.silentDeep(ChannelStats.Channel.BLOCK_EVENT));
	}

	@Test
	void lineShowsCountsAndCulledMarker() {
		ChannelStats stats = new ChannelStats();
		assertEquals("no events yet", stats.line());

		stats.count(ChannelStats.Channel.SOUND, 20, 0);
		stats.count(ChannelStats.Channel.SOUND, -40, 0);
		assertEquals("sound 2 (deep 1)", stats.line());

		for (int i = 0; i < 50; i++) {
			stats.count(ChannelStats.Channel.BLOCK_EVENT, 20, 0);
		}
		assertEquals("sound 2 (deep 1) | block_event 50 (deep 0, culled?)", stats.line());
	}

	@Test
	void resetClearsEveryChannel() {
		ChannelStats stats = new ChannelStats();
		stats.count(ChannelStats.Channel.XP_ORB, 0, 0);
		stats.count(ChannelStats.Channel.PARTICLE, -40, 0);
		stats.reset();
		assertEquals(0, stats.total(ChannelStats.Channel.XP_ORB));
		assertEquals(0, stats.deep(ChannelStats.Channel.PARTICLE));
		assertEquals("no events yet", stats.line());
	}
}
