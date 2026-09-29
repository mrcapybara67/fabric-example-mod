package com.griefer.client.module;

import java.util.ArrayList;
import java.util.List;

/**
 * Central module registry. Mixins and events read modules from here.
 */
public final class ModuleManager {
	private static final ModuleManager INSTANCE = new ModuleManager();

	private final List<Module> modules = new ArrayList<>();

	private ModuleManager() {
	}

	public static ModuleManager get() {
		return INSTANCE;
	}

	public void register(Module module) {
		modules.add(module);
	}

	public List<Module> modules() {
		return modules;
	}

	public List<Module> byCategory(Category category) {
		List<Module> out = new ArrayList<>();
		for (Module m : modules) {
			if (m.category() == category) {
				out.add(m);
			}
		}
		return out;
	}

	public <T extends Module> T get(Class<T> type) {
		for (Module m : modules) {
			if (type.isInstance(m)) {
				return type.cast(m);
			}
		}
		return null;
	}

	public int enabledCount() {
		int n = 0;
		for (Module m : modules) {
			if (m.isEnabled()) {
				n++;
			}
		}
		return n;
	}
}
