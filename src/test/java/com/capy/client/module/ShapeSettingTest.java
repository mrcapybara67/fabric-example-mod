package com.capy.client.module;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Shape Mode maps to the two layers the renderer asks for. */
class ShapeSettingTest {

	@Test
	void linesDrawsOnlyTheOutline() {
		ShapeSetting shape = new ShapeSetting("shape", "lines");
		assertTrue(shape.outlines());
		assertFalse(shape.fills());
	}

	@Test
	void fillDrawsOnlyTheFaces() {
		ShapeSetting shape = new ShapeSetting("shape", "fill");
		assertTrue(shape.fills());
		assertFalse(shape.outlines());
	}

	@Test
	void bothDrawsBothLayers() {
		ShapeSetting shape = new ShapeSetting("shape", "both");
		assertTrue(shape.fills());
		assertTrue(shape.outlines());
	}
}
