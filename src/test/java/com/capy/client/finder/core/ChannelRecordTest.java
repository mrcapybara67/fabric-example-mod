package com.capy.client.finder.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChannelRecordTest {
	@TempDir
	Path dir;

	@BeforeEach
	@AfterEach
	void clear() {
		ChannelRecord.clearForTests();
	}

	@Test
	void savesAndLoadsPerServer() {
		ChannelRecord.note(dir, "alpha.example", ChannelStats.Channel.SOUND);
		ChannelRecord.note(dir, "alpha.example", ChannelStats.Channel.DESTRUCTION);
		ChannelRecord.note(dir, "beta.example", ChannelStats.Channel.XP_ORB);
		ChannelRecord.flush(dir);
		assertTrue(Files.exists(dir.resolve(ChannelRecord.FILE_NAME)));

		ChannelRecord.clearForTests();

		assertEquals(Set.of("sound", "destruction"), ChannelRecord.deepChannels(dir, "alpha.example"));
		assertEquals(Set.of("xp_orb"), ChannelRecord.deepChannels(dir, "beta.example"));
		assertTrue(ChannelRecord.deepChannels(dir, "gamma.example").isEmpty());
	}

	@Test
	void oldConfigWithoutTheRecordLoadsCleanly() throws IOException {
		assertTrue(ChannelRecord.deepChannels(dir, "new.example").isEmpty());

		Files.writeString(dir.resolve(ChannelRecord.FILE_NAME), "{\"legacy.example\":[\"sound\"]}");
		ChannelRecord.clearForTests();
		assertEquals(Set.of("sound"), ChannelRecord.deepChannels(dir, "legacy.example"));
		assertTrue(ChannelRecord.deepChannels(dir, "new.example").isEmpty());
	}

	@Test
	void malformedRecordIsIgnored() throws IOException {
		Files.writeString(dir.resolve(ChannelRecord.FILE_NAME), "not json");
		assertTrue(ChannelRecord.deepChannels(dir, "server").isEmpty());
	}
}
