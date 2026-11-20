package com.capy.client.mixin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Startup audit for mixins that are allowed to fail silently.
 *
 * <p>Most hooks use {@code require = 0} so a changed mapping cannot prevent the
 * game from starting. The cost is that a hook which stops applying is
 * invisible. This plugin records every mixin Mixin actually applies, reads the
 * declared mixin list from the client config, and reports every declared mixin
 * that did not apply by the time the client finishes initialising.</p>
 *
 * <p>The report is deliberately one pass. A lazy target (a screen, for
 * example) can legitimately not be loaded at this point; that is exactly why
 * the line says "lazy target or failed injection" rather than claiming a
 * specific cause. The important case is a mixin that should have applied and
 * did not, which is now visible instead of silent.</p>
 */
public final class CapyMixinPlugin implements IMixinConfigPlugin {
	private static final Logger LOGGER = LoggerFactory.getLogger("capy-mixins");
	private static final Set<String> APPLIED = ConcurrentHashMap.newKeySet();
	private static final AtomicBoolean AUDITED = new AtomicBoolean();
	private static final Pattern CLIENT_ARRAY = Pattern.compile(
			"\"client\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
	private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");
	private static volatile List<String> expected;

	@Override
	public void onLoad(String mixinPackage) {
		LOGGER.info("Capy mixin audit active for {}", mixinPackage);
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return true;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
			IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
			IMixinInfo mixinInfo) {
		String simple = simpleName(mixinClassName);
		if (APPLIED.add(simple)) {
			LOGGER.info("Capy mixin applied: {} -> {}", simple, targetClassName);
		}
	}

	/**
	 * Logs one list of applied hooks and one line for every declared mixin that
	 * was not applied. Repeated calls are ignored, so a lazy class load cannot
	 * turn this into log spam.
	 */
	public static void audit() {
		if (!AUDITED.compareAndSet(false, true)) {
			return;
		}
		List<String> declared = expectedMixins();
		if (declared.isEmpty()) {
			LOGGER.warn("Capy mixin audit could not read capy.client.mixins.json");
			return;
		}
		LOGGER.info("Capy mixins applied at startup: {}", APPLIED);
		for (String mixin : declared) {
			if (!APPLIED.contains(mixin)) {
				LOGGER.warn("Capy mixin did not apply (lazy target or failed injection): {}", mixin);
			}
		}
	}

	private static String simpleName(String mixinClassName) {
		return mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
	}

	private static List<String> expectedMixins() {
		List<String> cached = expected;
		if (cached != null) {
			return cached;
		}
		List<String> names = new ArrayList<>();
		try (InputStream in = CapyMixinPlugin.class.getClassLoader()
				.getResourceAsStream("capy.client.mixins.json")) {
			if (in != null) {
				String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
				Matcher array = CLIENT_ARRAY.matcher(json);
				if (array.find()) {
					Matcher entry = QUOTED.matcher(array.group(1));
					while (entry.find()) {
						names.add(entry.group(1));
					}
				}
			}
		} catch (IOException | RuntimeException failure) {
			LOGGER.warn("Capy mixin audit could not read capy.client.mixins.json", failure);
		}
		expected = Collections.unmodifiableList(names);
		return expected;
	}
}
