package com.capy.client.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards the two mixin rules that keep the Controls screen safe: the key-binds
 * list is never mixed into, and every hook into a lazily loaded screen class is
 * explicitly optional.
 */
class MixinConfigTest {
	private static final Path CONFIG = Path.of("src/client/resources/capy.client.mixins.json");
	private static final Path MIXIN_DIR = Path.of("src/client/java/com/capy/client/mixin");
	private static final Pattern INJECTION = Pattern.compile(
			"@(Inject|Redirect|ModifyVariable|ModifyArg|ModifyArgs|ModifyConstant|"
					+ "ModifyExpressionValue|ModifyReceiver|ModifyReturnValue|"
					+ "WrapOperation|WrapWithCondition)\\s*\\(");
	private static final Pattern MIXIN = Pattern.compile("@Mixin\\s*\\(([^)]*)\\)");

	@Test
	void keyBindsListIsNotMixedInto() throws Exception {
		JsonObject root;
		try (Reader reader = Files.newBufferedReader(CONFIG)) {
			root = JsonParser.parseReader(reader).getAsJsonObject();
		}
		JsonArray client = root.getAsJsonArray("client");
		for (var entry : client) {
			assertFalse("KeyBindsListMixin".equals(entry.getAsString()), entry.getAsString());
		}
		assertFalse(Files.exists(MIXIN_DIR.resolve("KeyBindsListMixin.java")));
		assertEquals(0, root.getAsJsonObject("injectors").get("defaultRequire").getAsInt());
	}

	@Test
	void everyScreenInjectionIsOptional() throws Exception {
		JsonObject root;
		try (Reader reader = Files.newBufferedReader(CONFIG)) {
			root = JsonParser.parseReader(reader).getAsJsonObject();
		}
		int checked = 0;
		for (var entry : root.getAsJsonArray("client")) {
			String name = entry.getAsString();
			Path source = MIXIN_DIR.resolve(name + ".java");
			assertTrue(Files.exists(source), "missing mixin source " + name);
			String text = Files.readString(source);
			Matcher mixin = MIXIN.matcher(text);
			assertTrue(mixin.find(), "missing @Mixin in " + name);
			if (!loadsLazily(mixin.group(1))) {
				continue;
			}
			Matcher injection = INJECTION.matcher(text);
			int hooks = 0;
			while (injection.find()) {
				hooks++;
				int open = text.indexOf('(', injection.start());
				int end = annotationEnd(text, open);
				assertTrue(end > open, "unterminated injection in " + name);
				String block = text.substring(injection.start(), end);
				assertTrue(block.matches("(?s).*\\brequire\\s*=\\s*0\\b.*"),
						name + " has a screen injection without require = 0");
			}
			checked += hooks;
		}
		assertTrue(checked >= 2, "expected the category and container hooks to be audited");
	}

	private static boolean loadsLazily(String target) {
		String compact = target.replace(" ", "");
		return compact.contains("Screen") || compact.contains("KeyBindsList")
				|| compact.contains("KeyMapping.Category");
	}

	/** Finds the closing parenthesis of an annotation, ignoring parentheses in strings. */
	private static int annotationEnd(String text, int open) {
		if (open < 0) {
			return -1;
		}
		int depth = 0;
		boolean quoted = false;
		for (int i = open; i < text.length(); i++) {
			char c = text.charAt(i);
			if (quoted) {
				if (c == '\\') {
					i++;
				} else if (c == '"') {
					quoted = false;
				}
				continue;
			}
			if (c == '"') {
				quoted = true;
			} else if (c == '(') {
				depth++;
			} else if (c == ')') {
				depth--;
				if (depth == 0) {
					return i + 1;
				}
			}
		}
		return -1;
	}
}
