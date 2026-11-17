package com.capy.client.antivanish;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class AntiVanishSourceTest {
	private static final List<String> FORBIDDEN = List.of(
			".send(", "new Serverbound", "ServerboundCommandSuggestionPacket",
			"PacketSignals.probe(", "ClientPlayNetworking.send", "ClientConfigurationNetworking.send",
			"registerReceiver(", "registerGlobalReceiver(");

	@Test
	void onlyTheProbeClassMaySendAPacket() throws IOException {
		List<String> violations = new ArrayList<>();
		List<Path> roots = List.of(
				Path.of("src/client/java/com/capy/client/antivanish"),
				Path.of("src/client/java/com/capy/client/module/modules/antivanish"));
		for (Path root : roots) {
			try (Stream<Path> files = Files.walk(root)) {
				for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
					if (file.getFileName().toString().equals("VanishProbe.java")) {
						continue;
					}
					List<String> lines = Files.readAllLines(file);
					for (int i = 0; i < lines.size(); i++) {
						String line = lines.get(i).strip();
						if (line.startsWith("//") || line.startsWith("*")) {
							continue;
						}
						for (String needle : FORBIDDEN) {
							if (line.contains(needle)) {
								violations.add(file + ":" + (i + 1) + " -> " + line);
							}
						}
					}
				}
			}
		}
		assertTrue(violations.isEmpty(), String.join("\n", violations));
	}
}
