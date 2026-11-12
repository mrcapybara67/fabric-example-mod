package com.capy.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AnimTest {
	@Test
	void aSettledValueDoesNotIssueAnimationUpdates() {
		Anim.resetUpdateCount();
		Anim value = new Anim(1f, 12f);
		for (int i = 0; i < 100; i++) {
			value.to(1f, 0.05f);
		}
		assertEquals(0L, Anim.updateCount());
		assertEquals(1f, value.value(), 0.0001f);
		assertFalse(value.isMoving());
	}

	@Test
	void aMovingValueConvergesAndCountsUpdates() {
		Anim.resetUpdateCount();
		Anim value = new Anim(0f, 20f);
		for (int i = 0; i < 200; i++) {
			value.to(1f, 0.05f);
		}
		assertTrue(value.value() > 0.99f);
		assertTrue(Anim.updateCount() > 0);
	}

	@Test
	void easingStartsAndEndsAtTheRightValues() {
		assertEquals(0f, Motion.easeOutCubic(0f), 0.0001f);
		assertEquals(1f, Motion.easeOutCubic(1f), 0.0001f);
		assertEquals(0.5f, Motion.easeInOutCubic(0.5f), 0.0001f);
	}
}
