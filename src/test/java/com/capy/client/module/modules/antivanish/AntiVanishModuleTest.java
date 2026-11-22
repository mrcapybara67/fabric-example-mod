package com.capy.client.module.modules.antivanish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.capy.client.antivanish.core.DepartureTracker;
import com.capy.client.antivanish.core.VanishEngine;
import com.capy.client.module.ListResult;
import com.capy.client.module.WatchlistSetting;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AntiVanishModuleTest {
	private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private AntiVanishModule module;
	private FakeView view;

	@BeforeEach
	void setUp() {
		module = new AntiVanishModule();
		WatchlistSetting watchlist = module.settings().stream()
				.filter(WatchlistSetting.class::isInstance)
				.map(WatchlistSetting.class::cast)
				.findFirst()
				.orElseThrow();
		assertEquals(ListResult.ADDED, watchlist.add("Steve"));
		module.observeListed(STEVE, "Steve", true, 0);
		view = new FakeView();
	}

	@Test
	void vanishWithBodyStillNearBecomesVanishClueWithProof() {
		module.processRemove(STEVE, "Steve", 1, 2_000);
		view.listed = false;
		view.distance = 4;

		module.processDue(3_000, view);

		VanishEngine.Signal signal = onlySignal(VanishEngine.Type.VANISH, 3_000);
		assertEquals("Steve", signal.subject());
		assertEquals("Vanish Event: entity remained", signal.reason());
		assertNotNull(signal.proof());
		assertEquals(STEVE.toString(), signal.proof().key());
		assertEquals(4, signal.proof().distance());
	}

	@Test
	void normalLogoutProducesNoClue() {
		module.processRemove(STEVE, "Steve", 1, 2_000);
		module.processChat("Steve left the game", 2_100);
		view.listed = false;
		view.distance = 4;

		module.processDue(3_000, view);

		assertTrue(module.engine().signals(3_000).isEmpty());
	}

	@Test
	void hiddenEntryAfterLeaveMessageProducesNoClue() {
		module.processChat("Steve left the game", 1_600);
		module.processListed(STEVE, "Steve", false, 1, 2_000);

		assertTrue(module.engine().signals(2_000).isEmpty());
	}

	@Test
	void hiddenEntryWithoutLeaveMessageBecomesAVanishClue() {
		module.processListed(STEVE, "Steve", false, 1, 2_000);

		VanishEngine.Signal signal = onlySignal(VanishEngine.Type.VANISH, 2_000);
		assertEquals("Vanish Event: hidden from TAB", signal.reason());
		assertEquals(100, signal.weight());
	}

	@Test
	void bulkTabRemovalIsNotASingleVanish() {
		module.processRemove(STEVE, "Steve", 4, 2_000);
		view.listed = false;
		view.distance = 4;

		module.processDue(3_000, view);

		assertTrue(module.engine().signals(3_000).isEmpty());
	}

	@Test
	void invisibleBodyInsideSixtyFourBlocksBecomesAnInvisibleClue() {
		module.observeInvisibleBody(STEVE, "Steve", 64, 2_000);

		VanishEngine.Signal signal = onlySignal(VanishEngine.Type.INVISIBLE, 2_000);
		assertEquals("Invisible Entity: metadata flag", signal.reason());
		assertEquals(35, signal.weight());
		assertNotNull(signal.proof());
		assertEquals(64, signal.proof().distance());
	}

	@Test
	void invisibleBodyAtEightyBlocksDoesNotBecomeAClue() {
		module.observeInvisibleBody(STEVE, "Steve", 80, 2_000);

		assertTrue(module.engine().signals(2_000).isEmpty());
	}

	@Test
	void spectatorSwitchAloneIsNotAVanishClue() {
		module.observeGameMode(STEVE, "Steve", GameType.SURVIVAL.getId(), 1_000);
		module.observeGameMode(STEVE, "Steve", GameType.SPECTATOR.getId(), 2_000);

		assertTrue(module.engine().signals(2_000).isEmpty());
	}

	@Test
	void liveSpectatorBodyNearBecomesVanishAndInvisibleClues() {
		module.observeGameMode(STEVE, "Steve", GameType.SURVIVAL.getId(), 1_000);
		module.observeGameMode(STEVE, "Steve", GameType.SPECTATOR.getId(), 2_000);
		module.observeSpectatorBody(STEVE, "Steve", 5, 2_000);

		VanishEngine.Signal vanish = onlySignal(VanishEngine.Type.VANISH, 2_000);
		assertEquals("Vanish Event: spectator body", vanish.reason());
		assertNotNull(vanish.proof());
		assertEquals(5, vanish.proof().distance());
		assertNotNull(module.engine().signals(2_000).stream()
				.filter(signal -> signal.type() == VanishEngine.Type.INVISIBLE)
				.findFirst().orElse(null));

		module.observeGameMode(STEVE, "Steve", GameType.SURVIVAL.getId(), 3_000);

		assertFalse(module.hasActiveVanish("Steve", 3_000));
	}

	@Test
	void deathMessageSuppressesDeathScreenSpectator() {
		module.processChat("Steve was slain by Alex", 2_000);
		module.observeGameMode(STEVE, "Steve", GameType.SPECTATOR.getId(), 2_000);
		module.observeSpectatorBody(STEVE, "Steve", 5, 2_000);

		assertTrue(module.engine().signals(2_000).isEmpty());
	}

	@Test
	void deadSpectatorBodyIsNotAVanishClue() {
		module.observeGameMode(STEVE, "Steve", GameType.SURVIVAL.getId(), 1_000);
		module.observeGameMode(STEVE, "Steve", GameType.SPECTATOR.getId(), 2_000);
		module.observeDeath(STEVE, "Steve", 2_000);
		module.observeSpectatorBody(STEVE, "Steve", 5, 2_000);

		assertTrue(module.engine().signals(2_000).isEmpty());
	}

	@Test
	void deathClearsLeftoverInvisibleBodyClues() {
		module.observeInvisibleBody(STEVE, "Steve", 5, 2_000);
		assertFalse(module.engine().signals(2_000).isEmpty());

		module.observeDeath(STEVE, "Steve", 2_100);

		assertTrue(module.engine().signals(2_100).isEmpty());
	}

	@Test
	void deathSuppressesTabRemoval() {
		module.observeDeath(STEVE, "Steve", 2_000);
		module.processRemove(STEVE, "Steve", 1, 2_000);
		view.listed = false;
		view.distance = 5;

		module.processDue(3_000, view);

		assertTrue(module.engine().signals(3_000).isEmpty());
	}

	@Test
	void subjectMustBeListedForTheGracePeriodBeforeItIsCredible() {
		assertFalse(module.credible(STEVE, "Steve", 1_499));
		assertTrue(module.credible(STEVE, "Steve", 1_500));
	}

	@Test
	void unlistedSubjectIsNotCredible() {
		module.observeListed(STEVE, "Steve", false, 2_000);

		assertFalse(module.credible(STEVE, "Steve", 3_000));
	}

	private VanishEngine.Signal onlySignal(VanishEngine.Type type, long now) {
		List<VanishEngine.Signal> signals = module.engine().signals(now).stream()
				.filter(signal -> signal.type() == type)
				.toList();
		assertEquals(1, signals.size());
		return signals.getFirst();
	}

	private static final class FakeView implements DepartureTracker.View {
		private boolean listed;
		private int distance = -1;

		@Override
		public boolean listed(String uuid) {
			return listed;
		}

		@Override
		public int distanceIfNearby(String uuid) {
			return distance;
		}
	}
}
