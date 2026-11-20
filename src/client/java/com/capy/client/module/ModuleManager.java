package com.capy.client.module;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Central module registry. Mixins and events read modules from here.
 */
public final class ModuleManager {
	private static final ModuleManager INSTANCE = new ModuleManager();

	private final List<Module> modules = new ArrayList<>();
	/** Final state per module, applied on the next client tick. */
	private final Map<Module, Boolean> pendingToggles = new LinkedHashMap<>();

	private ModuleManager() {
	}

	public static ModuleManager get() {
		return INSTANCE;
	}

	public void register(Module module) {
		modules.add(module);
	}

	/** Removes a module; used by tests and by hot-reload tooling. */
	public void unregister(Module module) {
		modules.remove(module);
		pendingToggles.remove(module);
	}

	/** Queues the feature hook for the next tick; repeated toggles coalesce. */
	void queueToggle(Module module, boolean state) {
		pendingToggles.put(module, state);
	}

	/**
	 * Runs every deferred toggle once. This is called at the start of the client
	 * tick, not from a screen's mouse handler, so heavy module setup stays off
	 * the render path.
	 */
	public void flushPendingToggles() {
		if (pendingToggles.isEmpty()) {
			return;
		}
		List<Map.Entry<Module, Boolean>> batch = new ArrayList<>(pendingToggles.entrySet());
		pendingToggles.clear();
		for (Map.Entry<Module, Boolean> entry : batch) {
			entry.getKey().applyToggleState(entry.getValue());
		}
	}

	/** Number of hooks waiting for the next tick. */
	int pendingToggleCount() {
		return pendingToggles.size();
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
