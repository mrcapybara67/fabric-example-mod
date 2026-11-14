package com.capy.client.module.modules.esp;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.capy.client.finder.core.Chunks;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.junit.jupiter.api.Test;

class FinderFlagLifecycleTest {
	@Test
	void replacingTheFlagSetRemovesAStaleFlag() {
		TestFinder finder = new TestFinder();
		long key = Chunks.key(0, 0);
		finder.publish(key);
		assertNotNull(finder.flag(key));

		finder.publishNothing();

		assertNull(finder.flag(key));
		assertTrue(finder.flagRecords().isEmpty());
	}

	private static final class TestFinder extends FinderModule {
		TestFinder() {
			super("test_finder", 0xFFFF0000);
		}

		void publish(long key) {
			replaceFlags(Map.of(key, List.of()), "test");
		}

		void publishNothing() {
			replaceFlags(Map.of(), "test");
		}

		@Override
		public void onTick(Minecraft mc) {
		}

		@Override
		public boolean scan(ClientLevel level, LevelChunk chunk, long deadlineNanos) {
			return true;
		}
	}
}
