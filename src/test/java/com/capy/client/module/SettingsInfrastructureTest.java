package com.capy.client.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/** Task 1: range/step clamping, conditional visibility and generic persistence. */
class SettingsInfrastructureTest {

	@Test
	void intSettingSnapsToStepAndClamps() {
		IntSetting setting = new IntSetting("fill_intensity", 40, 5, 100, 5);
		assertEquals(40, setting.get().intValue());

		setting.set(3);
		assertEquals(5, setting.get().intValue(), "below the range clamps to min");

		setting.set(103);
		assertEquals(100, setting.get().intValue(), "above the range clamps to max");

		setting.set(43);
		assertEquals(45, setting.get().intValue(), "43 is nearest to the 45 stop");

		setting.set(42);
		assertEquals(40, setting.get().intValue(), "42 is nearest to the 40 stop");

		setting.set(null);
		assertEquals(40, setting.get().intValue(), "null restores the default");
	}

	@Test
	void intSettingDefaultIsSnappedOnConstruction() {
		IntSetting setting = new IntSetting("height", 63, 10, 320, 10);
		assertEquals(60, setting.get().intValue());
	}

	@Test
	void dependentVisibilityFollowsParentImmediately() {
		BooleanSetting parent = new BooleanSetting("follow_my_height", true);
		IntSetting child = new IntSetting("height", 63, -64, 320, 1);
		child.visibleWhen(() -> !parent.value());

		assertFalse(child.visible(), "the row is hidden while the parent is on");
		parent.set(false);
		assertTrue(child.visible(), "the row is visible in the same frame the parent changed");
		parent.toggle();
		assertFalse(child.visible());
	}

	@Test
	void tipIsCarriedThroughChaining() {
		IntSetting child = new IntSetting("height", 63, -64, 320, 1);
		child.visibleWhen(() -> true);
		child.tip("The plate's height when it does not follow you.");
		assertEquals("The plate's height when it does not follow you.", child.tip());
		assertTrue(child.visible());
	}

	@Test
	void genericCodecLoadsOldKeysAndRoundTrips() {
		TestModule module = new TestModule();
		JsonObject old = new JsonObject();
		old.addProperty("merge_radius", 6);
		old.addProperty("notify", true);
		old.addProperty("world_seed", "12345");
		old.addProperty("shape", "both");
		old.addProperty("tracers", true);
		old.addProperty("clamped", 999);

		module.loadSettings(old);
		assertEquals(6, module.mergeRadius.get().intValue());
		assertTrue(module.notify.value());
		assertEquals("12345", module.seed.value());
		assertEquals("both", module.shape.option());
		assertTrue(module.tracers.value());
		assertEquals(8, module.clamped.get().intValue(), "a hand-edited value clamps");

		JsonObject saved = new JsonObject();
		module.saveSettings(saved);
		TestModule reloaded = new TestModule();
		reloaded.loadSettings(saved);
		assertEquals(module.mergeRadius.get(), reloaded.mergeRadius.get());
		assertEquals(module.notify.value(), reloaded.notify.value());
		assertEquals(module.seed.value(), reloaded.seed.value());
		assertEquals(module.shape.option(), reloaded.shape.option());
		assertEquals(module.tracers.value(), reloaded.tracers.value());
		assertEquals(module.clamped.get(), reloaded.clamped.get());
	}

	@Test
	void genericCodecKeepsDefaultsWhenANewKeyIsMissing() {
		TestModule module = new TestModule();
		JsonObject old = new JsonObject();
		old.addProperty("merge_radius", 7);
		module.loadSettings(old);
		assertEquals(7, module.mergeRadius.get().intValue());
		assertFalse(module.notify.value(), "a key absent from an old file stays off");
		assertEquals("", module.seed.value());
		assertEquals("both", module.shape.option());
		assertFalse(module.tracers.value());
		assertEquals(4, module.clamped.get().intValue());
	}

	@Test
	void shapeSettingMapsToBothLayers() {
		ShapeSetting shape = new ShapeSetting("shape", ShapeSetting.LINES);
		assertTrue(shape.outlines());
		assertFalse(shape.fills());
		shape.select(ShapeSetting.FILL);
		assertFalse(shape.outlines());
		assertTrue(shape.fills());
		shape.select(ShapeSetting.BOTH);
		assertTrue(shape.outlines());
		assertTrue(shape.fills());
	}

	private static final class TestModule extends Module {
		final IntSetting mergeRadius = setting(new IntSetting("merge_radius", 4, 1, 8, 1));
		final BooleanSetting notify = setting(new BooleanSetting("notify", false));
		final StringSetting seed = setting(new StringSetting("world_seed", ""));
		final ShapeSetting shape = setting(new ShapeSetting("shape", ShapeSetting.BOTH));
		final BooleanSetting tracers = setting(new BooleanSetting("tracers", false));
		final IntSetting clamped = setting(new IntSetting("clamped", 4, 1, 8, 1));

		TestModule() {
			super(Category.BASE_FINDER, "settings_test");
		}
	}
}
