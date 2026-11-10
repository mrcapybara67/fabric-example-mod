package com.capy.client.module.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AtmosphereNeonVertexTest {
	@Test
	void neonVertexIsWorldMinusCameraAtLargeCoordinates() {
		assertEquals(-4137.0, AtmosphereMath.cameraRelative(-4137.0, 0.0), 0.0);
		assertEquals(-8437.0, AtmosphereMath.cameraRelative(-4137.0, 4300.0), 0.0);
	}

	@Test
	void neonHeightAndYAreConvertedAtEmission() {
		double worldX = -4137.0;
		double worldY = 70.0;
		double worldZ = 2995.0;
		double cameraX = 4300.0;
		double cameraY = 64.0;
		double cameraZ = 2950.0;
		assertEquals(-8437.0, AtmosphereMath.cameraRelative(worldX, cameraX), 0.0);
		assertEquals(6.0, AtmosphereMath.cameraRelative(worldY, cameraY), 0.0);
		assertEquals(45.0, AtmosphereMath.cameraRelative(worldZ, cameraZ), 0.0);
	}
}
