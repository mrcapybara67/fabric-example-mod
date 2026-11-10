package com.capy.client.module.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AtmosphereSkyGeometryTest {
	@Test
	void everyPrimitiveEmitsWholeQuadsWithFiniteVertices() {
		CountingConsumer counting = new CountingConsumer();
		VertexConsumer consumer = counting.proxy();
		PoseStack.Pose pose = new PoseStack().last();
		AtmosphereSkyRenderer.Sky sky = new AtmosphereSkyRenderer.Sky();
		sky.set(consumer, pose);

		check(counting, "dome", () -> sky.dome(9));
		check(counting, "sun", () -> sky.sun(12, 20, 5, 0xFFFFFFFF, 0x40FFFFFF));
		check(counting, "moon", () -> sky.moon(-20, 40, 7, 0.25f, 0xFFFFFFFF, 0xFF101020, 32));
		check(counting, "band", () -> sky.band(12, 20, 4, 8, 0x10FFFFFF, 0x80FFFFFF));
		check(counting, "ring", () -> sky.ring(30, 0.8, 32, 0x10FFFFFF, 0x80FFFFFF));
		check(counting, "puff", () -> sky.puff(20, 30, 8, 4, 0x40FFFFFF));
		check(counting, "star", () -> sky.star(40, 50, 0.4, 120, 0xFFFFFFFF));
		check(counting, "curtain", () -> sky.curtain(-80, 160, 17, 59, 11, 0.4, 0x80FFFFFF, 0x10FFFFFF));
		check(counting, "streak", () -> sky.streak(10, 60, 20, 55, 0xFFFFFFFF, 0x00FFFFFF));
		check(counting, "rays", () -> sky.rays(30, 20, 12, 0.0, 0x40FFFFFF));
		check(counting, "striped sun", () -> sky.stripedSun(0, 22, 16, 1.2));
		check(counting, "debug", () -> sky.debug(1.2));
		assertTrue(counting.total.get() > 0, "no vertices were emitted");
	}

	private static void check(CountingConsumer counting, String name, Runnable draw) {
		int before = counting.count.get();
		draw.run();
		int emitted = counting.count.get() - before;
		assertTrue(emitted > 0, name + " emitted no vertices");
		assertEquals(0, emitted % 4, name + " emitted a partial quad");
		assertTrue(counting.finite.get(), name + " emitted a non-finite coordinate");
	}

	private static final class CountingConsumer {
		private final AtomicInteger count = new AtomicInteger();
		private final AtomicBoolean finite = new AtomicBoolean(true);
		private final AtomicInteger total = new AtomicInteger();

		VertexConsumer proxy() {
			return (VertexConsumer) Proxy.newProxyInstance(
					VertexConsumer.class.getClassLoader(),
					new Class<?>[]{VertexConsumer.class},
					(proxy, method, args) -> {
						if ("addVertex".equals(method.getName())) {
							count.incrementAndGet();
							total.incrementAndGet();
							for (Object arg : args) {
								if (arg instanceof Float value && !Float.isFinite(value)) {
									finite.set(false);
								}
								if (arg instanceof Double value && !Double.isFinite(value)) {
									finite.set(false);
								}
							}
						}
						Class<?> type = method.getReturnType();
						if (type == VertexConsumer.class) {
							return proxy;
						}
						if (type == boolean.class) {
							return false;
						}
						if (type == int.class) {
							return 0;
						}
						if (type == float.class) {
							return 0f;
						}
						return null;
					});
		}
	}
}
