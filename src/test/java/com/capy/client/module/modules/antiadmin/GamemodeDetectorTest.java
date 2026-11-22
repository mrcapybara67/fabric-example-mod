package com.capy.client.module.modules.antiadmin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.Test;

class GamemodeDetectorTest {
	private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-000000000001");

	@Test
	void spectatorSwitchAfterDeathIsIgnored() {
		SignalBuffer buffer = new SignalBuffer();
		DeathTracker deaths = new DeathTracker();
		GamemodeDetector detector = new GamemodeDetector(buffer, deaths);
		detector.onEnable();
		buffer.tick(1);
		detector.onPacket(update(GameType.SURVIVAL, 1_000));
		deaths.death(STEVE, 2_000);
		detector.onPacket(update(GameType.SPECTATOR, 2_000));

		assertTrue(buffer.snapshot().isEmpty());
	}

	@Test
	void spectatorSwitchWhileAliveStillReports() {
		SignalBuffer buffer = new SignalBuffer();
		GamemodeDetector detector = new GamemodeDetector(buffer, new DeathTracker());
		detector.onEnable();
		buffer.tick(1);
		detector.onPacket(update(GameType.SURVIVAL, 1_000));
		detector.onPacket(update(GameType.SPECTATOR, 2_000));

		assertFalse(buffer.snapshot().isEmpty());
	}

	private static PacketSignals.InfoUpdate update(GameType mode, long now) {
		return new PacketSignals.InfoUpdate(STEVE, "Steve", null, mode.getId(), null, now);
	}
}
