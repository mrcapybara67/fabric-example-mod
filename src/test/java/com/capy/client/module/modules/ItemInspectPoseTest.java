package com.capy.client.module.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class ItemInspectPoseTest {
	@Test
	void swordAndPickaxeUseDifferentRotationAxesAtTheSameProgress() {
		Matrix4f sword = matrix(ItemInspectModule.Tool.SWORD, 0.5f, false);
		Matrix4f pickaxe = matrix(ItemInspectModule.Tool.PICKAXE, 0.5f, false);
		assertTrue(differs(sword, pickaxe));
	}

	@Test
	void everyInspectionStartsAndEndsAtTheVanillaPose() {
		for (ItemInspectModule.Tool tool : ItemInspectModule.Tool.values()) {
			assertEquals(new Matrix4f(), matrix(tool, 0f, false));
			assertEquals(new Matrix4f(), matrix(tool, 1f, false));
			assertEquals(new Matrix4f(), matrix(tool, 0f, true));
			assertEquals(new Matrix4f(), matrix(tool, 1f, true));
		}
	}

	@Test
	void everyInspectionProfileLooksDifferentWhileHeldUp() {
		ItemInspectModule.Tool[] tools = ItemInspectModule.Tool.values();
		for (int i = 0; i < tools.length; i++) {
			for (int j = i + 1; j < tools.length; j++) {
				Matrix4f a = matrix(tools[i], 0.5f, false);
				Matrix4f b = matrix(tools[j], 0.5f, false);
				assertTrue(differs(a, b), tools[i] + " and " + tools[j] + " share a pose");
			}
		}
	}

	@Test
	void swordAndAxeHaveDistinctFullTurnFlourishes() {
		assertTrue(ItemInspectPose.spinForTest(ItemInspectModule.Tool.SWORD, 0.70f) > 350f);
		assertTrue(ItemInspectPose.spinForTest(ItemInspectModule.Tool.AXE, 0.70f) < -350f);
		assertFalse(ItemInspectPose.spinRollForTest(ItemInspectModule.Tool.SWORD));
		assertTrue(ItemInspectPose.spinRollForTest(ItemInspectModule.Tool.AXE));
		assertEquals(0f, ItemInspectPose.spinForTest(ItemInspectModule.Tool.SWORD, 0.72f), 0.0001f);
		assertEquals(0f, ItemInspectPose.spinForTest(ItemInspectModule.Tool.PICKAXE, 0.70f), 0.0001f);
	}

	@Test
	void leftHandMirrorsTheRightHandAcrossTheGrip() {
		Matrix4f right = matrix(ItemInspectModule.Tool.SWORD, 0.5f, false);
		Matrix4f left = matrix(ItemInspectModule.Tool.SWORD, 0.5f, true);
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

	private static Matrix4f matrix(ItemInspectModule.Tool tool, float raw, boolean left) {
		PoseStack pose = new PoseStack();
		ItemInspectPose.applyForTest(pose, tool, raw, left);
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
