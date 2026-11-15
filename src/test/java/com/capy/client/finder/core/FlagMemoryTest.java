package com.capy.client.finder.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlagMemoryTest {
	@TempDir
	Path dir;

	@Test
	void roundTripsInTheConfigFolder() {
		FlagMemory.clearForTests();
		List<FlagMemory.Stored> values = List.of(
				new FlagMemory.Stored(1L, "first", 2),
				new FlagMemory.Stored(2L, "second", 3));
		FlagMemory.save(dir, "example.org", "minecraft:overworld", "hive_finder", values);
		FlagMemory.flush(dir);
		FlagMemory.clearForTests();
		assertEquals(values, FlagMemory.load(dir, "example.org", "minecraft:overworld", "hive_finder"));
	}

	@Test
	void keepsTheNewestFiveHundredAndTwelve() {
		List<FlagMemory.Stored> many = new ArrayList<>();
		for (int i = 0; i < 700; i++) {
			many.add(new FlagMemory.Stored(i, "flag " + i, 1));
		}
		List<FlagMemory.Stored> capped = FlagMemory.cap(many);
		assertEquals(FlagMemory.MAX_FLAGS, capped.size());
		assertEquals(188L, capped.get(0).chunkKey());
		assertEquals(699L, capped.get(capped.size() - 1).chunkKey());
	}
}
