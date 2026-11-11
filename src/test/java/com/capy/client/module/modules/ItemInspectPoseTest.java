package com.capy.client.module.modules;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class ItemInspectPoseTest {
	@Test
	void swordAndPickaxeUseDifferentRotationAxesAtTheSameProgress() {
		PoseStack sword = new PoseStack();
		PoseStack pickaxe = new PoseStack();
		ItemInspectPose.applyForTest(sword, ItemInspectModule.Tool.SWORD, 0.5f, false);
		ItemInspectPose.applyForTest(pickaxe, ItemInspectModule.Tool.PICKAXE, 0.5f, false);
		Matrix4f a = sword.last().pose();
		Matrix4f b = pickaxe.last().pose();
		boolean differs = Math.abs(a.m00() - b.m00()) > 1.0e-4f
				|| Math.abs(a.m11() - b.m11()) > 1.0e-4f
				|| Math.abs(a.m22() - b.m22()) > 1.0e-4f;
		assertTrue(differs);
	}

}
