package com.capy.client.antivanish.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DepartureTrackerTest {
	private static final String STEVE_UUID = "00000000-0000-0000-0000-000000000001";
	private static final String ALEX_UUID = "00000000-0000-0000-0000-000000000002";

	private DepartureTracker tracker;
	private FakeView view;

	@BeforeEach
	void setUp() {
		tracker = new DepartureTracker();
		view = new FakeView();
	}

	@Test
	void normalLogoutProducesNothingAndTeachesLeaveMessages() {
		tracker.tabRemoved(STEVE_UUID, "Steve", 1_000);
		tracker.chat("Steve left the game", java.util.List.<String[]>of(pair(STEVE_UUID, "Steve")), 1_500);

		assertTrue(tracker.serverSendsLeaveMessages());
		assertTrue(tracker.due(3_000, view).isEmpty());
	}

	@Test
	void bodyStillNearGivesEntityRemainedWithDistance() {
		tracker.tabRemoved(STEVE_UUID, "Steve", 1_000);
		view.distances.put(STEVE_UUID, 4);

		DepartureTracker.Finding finding = tracker.due(2_000, view).getFirst();
		assertEquals("Steve", finding.name());
		assertEquals(4, finding.distance());
		assertEquals(100, finding.score());
		assertTrue(finding.reason().contains("entity remained"));
	}

	@Test
	void silentRemovalCountsOnlyWhenTheServerSendsLeaveMessages() {
		tracker.tabRemoved(STEVE_UUID, "Steve", 1_000);
		assertTrue(tracker.due(2_000, view).isEmpty());

		DepartureTracker taught = new DepartureTracker();
		taught.chat("Alex left the game", java.util.List.<String[]>of(pair(ALEX_UUID, "Alex")), 1_000);
		taught.tabRemoved(STEVE_UUID, "Steve", 2_000);

		DepartureTracker.Finding finding = taught.due(3_000, view).getFirst();
		assertEquals("Steve", finding.name());
		assertEquals(-1, finding.distance());
		assertTrue(finding.reason().contains("silent TAB disappearance"));
	}

	@Test
	void comingBackToListedTabCancelsThePendingVanish() {
		tracker.tabRemoved(STEVE_UUID, "Steve", 1_000);
		view.listed.add(STEVE_UUID);

		assertTrue(tracker.due(2_000, view).isEmpty());
	}

	@Test
	void hiddenEntryAfterALeaveMessageIsIgnored() {
		tracker.chat("Steve left the game", java.util.List.<String[]>of(pair(STEVE_UUID, "Steve")), 1_000);

		assertNull(tracker.hiddenFromTab(STEVE_UUID, "Steve", 1_500));
	}

	@Test
	void hiddenEntryWithoutALeaveMessageIsAVanishFinding() {
		DepartureTracker.Finding finding = tracker.hiddenFromTab(STEVE_UUID, "Steve", 1_000);

		assertNotNull(finding);
		assertEquals(STEVE_UUID, finding.uuid());
		assertEquals("Steve", finding.name());
		assertEquals(100, finding.score());
		assertTrue(finding.reason().contains("hidden from TAB"));
	}

	@Test
	void hiddenEntryWithAnImplausibleNameIsIgnored() {
		assertNull(tracker.hiddenFromTab(STEVE_UUID, "x", 1_000));
	}

	@Test
	void clearDropsPendingStateAndLeaveKnowledge() {
		tracker.tabRemoved(STEVE_UUID, "Steve", 1_000);
		tracker.chat("Steve left the game", java.util.List.<String[]>of(pair(STEVE_UUID, "Steve")), 1_500);
		tracker.clear();

		assertFalse(tracker.serverSendsLeaveMessages());
		assertTrue(tracker.due(3_000, view).isEmpty());
	}

	private static String[] pair(String uuid, String name) {
		return new String[]{uuid, name};
	}

	private static final class FakeView implements DepartureTracker.View {
		private final Set<String> listed = new HashSet<>();
		private final Map<String, Integer> distances = new HashMap<>();

		@Override
		public boolean listed(String uuid) {
			return listed.contains(uuid);
		}

		@Override
		public int distanceIfNearby(String uuid) {
			return distances.getOrDefault(uuid, -1);
		}
	}
}
