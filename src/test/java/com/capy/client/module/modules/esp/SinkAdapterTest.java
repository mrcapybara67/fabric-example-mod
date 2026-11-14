package com.capy.client.module.modules.esp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class SinkAdapterTest {
	@Test
	void quadsEmitFourVerticesEach() {
		RecordingTarget target = new RecordingTarget();
		SinkAdapter sink = new SinkAdapter(target, Vec3.ZERO, null);

		sink.quad(0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1, 0xFF112233);
		sink.quad(2, 0, 0, 3, 0, 0, 3, 0, 1, 2, 0, 1, 0xFF112233);

		assertEquals(8, target.quadVertices);
		assertEquals(8, sink.quadVertices());
		assertEquals(0, sink.quadVertices() % 4);
	}

	@Test
	void cameraPositionIsSubtractedFromWorldVertices() {
		RecordingTarget target = new RecordingTarget();
		Vec3 camera = new Vec3(-4137, 70, 2995);
		SinkAdapter sink = new SinkAdapter(target, camera, null);

		sink.quad(-4137, 70, 2995, -4137, 70, 2995, -4137, 70, 2995, -4137, 70, 2995, 0xFFFFFFFF);

		assertEquals(0, target.firstX, 1.0e-9);
		assertEquals(0, target.firstY, 1.0e-9);
		assertEquals(0, target.firstZ, 1.0e-9);
	}

	private static final class RecordingTarget implements SinkAdapter.Target {
		private int quadVertices;
		private int lineVertices;
		private double firstX;
		private double firstY;
		private double firstZ;

		@Override
		public void quad(double x0, double y0, double z0, double x1, double y1, double z1,
				double x2, double y2, double z2, double x3, double y3, double z3, int argb) {
			quadVertices += 4;
			firstX = x0;
			firstY = y0;
			firstZ = z0;
		}

		@Override
		public void line(double x0, double y0, double z0, double x1, double y1, double z1,
				float width, int argb) {
			lineVertices += 2;
		}
	}
}
