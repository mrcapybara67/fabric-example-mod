package com.capy.client.stealth;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class StealthSourceTest {
	private static final List<String> FORBIDDEN = List.of(
			"connection.send(", ".send(new Serverbound", "ClientPlayNetworking.send",
			"ClientConfigurationNetworking.send", "registerReceiver(", "registerGlobalReceiver(",
			"PayloadTypeRegistry", "CustomPacketPayload.Type", "new ServerboundCustomPayloadPacket");

	@Test
	void finderSourcesNeverSendOrRegisterAChannel() throws IOException {
		List<String> violations = new ArrayList<>();
		for (Path root : List.of(Path.of("src/client/java/com/capy/client/module/modules/esp"),
				Path.of("src/client/java/com/capy/client/finder/core"),
				Path.of("src/client/java/com/capy/client/mixin/ClientPacketListenerMixin.java"))) {
			try (Stream<Path> files = Files.walk(root)) {
				for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
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
