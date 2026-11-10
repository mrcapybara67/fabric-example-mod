package com.capy.client.mixin;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Startup audit for mixins that are allowed to fail silently.
 *
 * <p>Several compatibility hooks use {@code require = 0} so a changed mapping
 * cannot prevent the game from starting. The cost is that a hook which stops
 * applying is invisible. This plugin records every mixin Mixin actually applies
 * and logs the expected require-zero hooks that were not applied.</p>
 */
public final class CapyMixinPlugin implements IMixinConfigPlugin {
	private static final Logger LOGGER = LoggerFactory.getLogger("capy-mixins");
	private static final Set<String> APPLIED = ConcurrentHashMap.newKeySet();
	private static final List<String> OPTIONAL = List.of(
			"AtmosphereFogMixin", "AtmosphereHazeMixin", "AtmosphereSkyMixin",
			"AtmosphereTimeMixin", "FreecamCameraMixin", "FreecamMouseMixin",
			"FreelookCameraMixin", "FreelookMouseMixin");

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
		String simple = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
		if (APPLIED.add(simple)) {
			LOGGER.info("Capy mixin applied: {} -> {}", simple, targetClassName);
		}
	}

	/** Logs one list of applied and one list of optional hooks that did not apply. */
	public static void audit() {
		LOGGER.info("Capy mixins applied: {}", APPLIED);
		for (String optional : OPTIONAL) {
			if (!APPLIED.contains(optional)) {
				LOGGER.warn("Capy optional mixin did not apply: {}", optional);
			}
		}
	}
}
