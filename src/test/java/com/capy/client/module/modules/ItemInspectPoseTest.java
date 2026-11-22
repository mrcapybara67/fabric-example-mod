package com.capy.client.module.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class ItemInspectPoseTest {
	@Test
	void everyWeaponHasThreeDistinctVariants() {
		assertEquals(3, ItemInspectPose.Variant.values().length);
		for (ItemInspectModule.Tool tool : ItemInspectModule.Tool.values()) {
			Matrix4f side = matrix(tool, ItemInspectPose.Variant.SIDE, 0.52f, false);
			Matrix4f turn = matrix(tool, ItemInspectPose.Variant.TURN, 0.52f, false);
			Matrix4f flip = matrix(tool, ItemInspectPose.Variant.FLIP, 0.52f, false);
			assertTrue(differs(side, turn), tool + " SIDE and TURN share a pose");
			assertTrue(differs(side, flip), tool + " SIDE and FLIP share a pose");
			assertTrue(differs(turn, flip), tool + " TURN and FLIP share a pose");
		}
	}

	@Test
	void everyVariantStartsAndEndsAtTheVanillaPose() {
		for (ItemInspectModule.Tool tool : ItemInspectModule.Tool.values()) {
			for (ItemInspectPose.Variant variant : ItemInspectPose.Variant.values()) {
				assertEquals(new Matrix4f(), matrix(tool, variant, 0f, false));
				assertEquals(new Matrix4f(), matrix(tool, variant, 1f, false));
				assertEquals(new Matrix4f(), matrix(tool, variant, 0f, true));
				assertEquals(new Matrix4f(), matrix(tool, variant, 1f, true));
			}
		}
	}

	@Test
	void everyInspectionProfileLooksDifferentWhileHeldUp() {
		ItemInspectModule.Tool[] tools = ItemInspectModule.Tool.values();
		for (int i = 0; i < tools.length; i++) {
			for (int j = i + 1; j < tools.length; j++) {
				Matrix4f a = matrix(tools[i], ItemInspectPose.Variant.SIDE, 0.5f, false);
				Matrix4f b = matrix(tools[j], ItemInspectPose.Variant.SIDE, 0.5f, false);
				assertTrue(differs(a, b), tools[i] + " and " + tools[j] + " share a pose");
			}
		}
	}

	@Test
	void swordAndAxeHaveOppositeFullTurns() {
		assertTrue(ItemInspectPose.spinForTest(
				ItemInspectModule.Tool.SWORD, ItemInspectPose.Variant.TURN, 0.68f) > 350f);
		assertTrue(ItemInspectPose.spinForTest(
				ItemInspectModule.Tool.AXE, ItemInspectPose.Variant.TURN, 0.68f) < -350f);
		assertTrue(ItemInspectPose.spinForTest(
				ItemInspectModule.Tool.SWORD, ItemInspectPose.Variant.FLIP, 0.60f) < -350f);
		assertTrue(ItemInspectPose.spinForTest(
				ItemInspectModule.Tool.AXE, ItemInspectPose.Variant.FLIP, 0.60f) > 350f);
		assertEquals(0f, ItemInspectPose.spinForTest(
				ItemInspectModule.Tool.SWORD, ItemInspectPose.Variant.TURN, 0.72f), 0.0001f);
		assertEquals(0f, ItemInspectPose.spinForTest(
				ItemInspectModule.Tool.SWORD, ItemInspectPose.Variant.FLIP, 0.64f), 0.0001f);
		assertEquals(0f, ItemInspectPose.spinForTest(
				ItemInspectModule.Tool.PICKAXE, ItemInspectPose.Variant.SIDE, 0.5f), 0.0001f);
	}

	@Test
	void everyWeaponHasTwoFullTurnOptions() {
		for (ItemInspectModule.Tool tool : ItemInspectModule.Tool.values()) {
			assertTrue(Math.abs(ItemInspectPose.spinForTest(
					tool, ItemInspectPose.Variant.TURN, 0.68f)) > 350f,
					tool + " TURN is not a full turn");
			assertTrue(Math.abs(ItemInspectPose.spinForTest(
					tool, ItemInspectPose.Variant.FLIP, 0.60f)) > 350f,
					tool + " FLIP is not a full turn");
		}
	}

	@Test
	void leftHandMirrorsTheRightHandAcrossTheGrip() {
		Matrix4f right = matrix(ItemInspectModule.Tool.SWORD, ItemInspectPose.Variant.SIDE, 0.5f, false);
		Matrix4f left = matrix(ItemInspectModule.Tool.SWORD, ItemInspectPose.Variant.SIDE, 0.5f, true);
		assertTrue(right.m30() > 0f);
		assertTrue(left.m30() < 0f);
		assertEquals(-right.m30(), left.m30(), 1.0e-4f);
	}

	@Test
	void spearHasTheLongestInspectionWindow() {
		for (ItemInspectModule.Tool tool : ItemInspectModule.Tool.values()) {
			if (tool != ItemInspectModule.Tool.SPEAR) {
				assertTrue(tool.durationNanos() < ItemInspectModule.Tool.SPEAR.durationNanos());
			}
		}
	}

	private static Matrix4f matrix(ItemInspectModule.Tool tool,
			ItemInspectPose.Variant variant, float raw, boolean left) {
		PoseStack pose = new PoseStack();
		ItemInspectPose.applyForTest(pose, tool, variant, raw, left);
		return pose.last().pose();
	}

	private static boolean differs(Matrix4f a, Matrix4f b) {
		return Math.abs(a.m00() - b.m00()) > 1.0e-4f
				|| Math.abs(a.m01() - b.m01()) > 1.0e-4f
				|| Math.abs(a.m02() - b.m02()) > 1.0e-4f
				|| Math.abs(a.m10() - b.m10()) > 1.0e-4f
				|| Math.abs(a.m11() - b.m11()) > 1.0e-4f
				|| Math.abs(a.m12() - b.m12()) > 1.0e-4f
				|| Math.abs(a.m20() - b.m20()) > 1.0e-4f
				|| Math.abs(a.m21() - b.m21()) > 1.0e-4f
				|| Math.abs(a.m22() - b.m22()) > 1.0e-4f
				|| Math.abs(a.m30() - b.m30()) > 1.0e-4f
				|| Math.abs(a.m31() - b.m31()) > 1.0e-4f
				|| Math.abs(a.m32() - b.m32()) > 1.0e-4f;
	}

	@Test
	void identityCheckIsNotAccidentallyAlwaysTrue() {
		assertFalse(differs(new Matrix4f(), new Matrix4f()));
	}
}
