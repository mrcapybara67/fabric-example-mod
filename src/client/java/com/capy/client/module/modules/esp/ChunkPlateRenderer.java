package com.capy.client.module.modules.esp;

import com.capy.client.util.Guard;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Draws merged chunk plates for the finders. Geometry is rebuilt only on change. */
public final class ChunkPlateRenderer {
	public record Rect(int minX, int minZ, int maxX, int maxZ) {
		public int width() {
			return maxX - minX + 1;
		}

		public int depth() {
			return maxZ - minZ + 1;
		}
	}

	private static final Map<BaseFinderModule, Cache> CACHES = new IdentityHashMap<>();
	private static final Comparator<Long> CHUNK_ORDER = Comparator
			.comparingInt((Long key) -> HeatMap.z(key))
			.thenComparingInt(HeatMap::x);
	private static MultiBufferSource.BufferSource batch;
	private static ByteBufferBuilder buffer;

	private ChunkPlateRenderer() {
	}

	private static final class Cache {
		long version = Long.MIN_VALUE;
		int style;
		final List<Rect> rects = new ArrayList<>();
		final List<Integer> colors = new ArrayList<>();
	}

	public static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			CACHES.clear();
			return;
		}
		List<BaseFinderModule> active = new ArrayList<>();
		for (var module : com.capy.client.module.ModuleManager.get().modules()) {
			if (module instanceof BaseFinderModule finder && finder.isEnabled()) {
				active.add(finder);
			}
		}
		if (active.isEmpty()) {
			CACHES.clear();
			return;
		}
		try {
			Vec3 camera = mc.gameRenderer.getMainCamera().position();
			PoseStack stack = context.matrices();
			if (stack == null) {
				stack = new PoseStack();
			}
			PoseStack.Pose pose = stack.last();
			MultiBufferSource.BufferSource batches = batch();
			VertexConsumer fills = batches.getBuffer(EspRenderTypes.quads());
			for (BaseFinderModule finder : active) {
				Cache cache = CACHES.computeIfAbsent(finder, key -> new Cache());
				int style = style(finder);
				if (cache.version != finder.plateVersion() || cache.style != style) {
					cache.version = finder.plateVersion();
					cache.style = style;
					rebuild(finder, cache);
				}
				for (int i = 0; i < cache.rects.size(); i++) {
					Rect rect = cache.rects.get(i);
					double x1 = rect.minX() * 16.0 - camera.x;
					double z1 = rect.minZ() * 16.0 - camera.z;
					double x2 = (rect.maxX() + 1) * 16.0 - camera.x;
					double z2 = (rect.maxZ() + 1) * 16.0 - camera.z;
					double y = finder.plateY() - camera.y;
					int color = cache.colors.get(i);
					fill(fills, pose, x1, y, z1, x2, y + finder.plateThickness(), z2, color);
				}
				if (finder.outlines()) {
					VertexConsumer lines = batches.getBuffer(EspRenderTypes.lines());
					for (int i = 0; i < cache.rects.size(); i++) {
						Rect rect = cache.rects.get(i);
						double x1 = rect.minX() * 16.0 - camera.x;
						double z1 = rect.minZ() * 16.0 - camera.z;
						double x2 = (rect.maxX() + 1) * 16.0 - camera.x;
						double z2 = (rect.maxZ() + 1) * 16.0 - camera.z;
						double y = finder.plateY() - camera.y + finder.plateThickness();
						int edge = 0xFF000000 | (cache.colors.get(i) & 0x00FFFFFF);
						edge = (Math.max(80, cache.colors.get(i) >>> 24) << 24) | (edge & 0x00FFFFFF);
						line(lines, pose, x1, y, z1, x2, y, z1, edge);
						line(lines, pose, x2, y, z1, x2, y, z2, edge);
						line(lines, pose, x2, y, z2, x1, y, z2, edge);
						line(lines, pose, x1, y, z2, x1, y, z1, edge);
					}
				}
				if (finder.renderClusters()) {
					VertexConsumer cluster = batches.getBuffer(EspRenderTypes.quads());
					for (AABB box : finder.boxes()) {
						fill(cluster, pose, box.minX - camera.x, box.minY - camera.y,
								box.minZ - camera.z, box.maxX - camera.x, box.maxY - camera.y,
								box.maxZ - camera.z, withAlpha(finder.plateColor(0), 55));
					}
				}
			}
			batches.endBatch();
			CACHES.keySet().retainAll(active);
		} catch (RuntimeException | LinkageError failure) {
			discard();
			Guard.report("chunk plates", failure);
		}
	}

	private static int style(BaseFinderModule finder) {
		return ((finder.plateY() * 31 + finder.plateThickness()) * 31
				+ (finder.outlines() ? 1 : 0)) * 31 + finder.plateStyle();
	}

	private static void rebuild(BaseFinderModule finder, Cache cache) {
		cache.rects.clear();
		cache.colors.clear();
		List<Rect> merged = merge(finder.plateChunks());
		for (Rect rect : merged) {
			cache.rects.add(rect);
			cache.colors.add(finder.plateColor(HeatMap.key(rect.minX(), rect.minZ())));
		}
	}

	/** Greedy maximal rectangles: run right, then down while every row is full. */
	public static List<Rect> merge(Set<Long> chunks) {
		List<Long> ordered = new ArrayList<>(chunks);
		ordered.sort(CHUNK_ORDER);
		Set<Long> remaining = new HashSet<>(chunks);
		List<Rect> rects = new ArrayList<>();
		for (long key : ordered) {
			if (!remaining.contains(key)) {
				continue;
			}
			int x = HeatMap.x(key);
			int z = HeatMap.z(key);
			int maxX = x;
			while (remaining.contains(HeatMap.key(maxX + 1, z))) {
				maxX++;
			}
			int maxZ = z;
			while (rowFull(remaining, x, maxX, maxZ + 1)) {
				maxZ++;
			}
			for (int zz = z; zz <= maxZ; zz++) {
				for (int xx = x; xx <= maxX; xx++) {
					remaining.remove(HeatMap.key(xx, zz));
				}
			}
			rects.add(new Rect(x, z, maxX, maxZ));
		}
		return rects;
	}

	private static boolean rowFull(Set<Long> chunks, int minX, int maxX, int z) {
		for (int x = minX; x <= maxX; x++) {
			if (!chunks.contains(HeatMap.key(x, z))) {
				return false;
			}
		}
		return true;
	}

	private static void fill(VertexConsumer vc, PoseStack.Pose pose,
			double x1, double y1, double z1, double x2, double y2, double z2, int color) {
		int top = color;
		int side = withAlpha(color, Math.max(0, (color >>> 24) / 2));
		vc.addVertex(pose, (float) x1, (float) y2, (float) z1).setColor(top);
		vc.addVertex(pose, (float) x1, (float) y2, (float) z2).setColor(top);
		vc.addVertex(pose, (float) x2, (float) y2, (float) z2).setColor(top);
		vc.addVertex(pose, (float) x2, (float) y2, (float) z1).setColor(top);
		vc.addVertex(pose, (float) x1, (float) y1, (float) z1).setColor(side);
		vc.addVertex(pose, (float) x1, (float) y1, (float) z2).setColor(side);
		vc.addVertex(pose, (float) x1, (float) y2, (float) z2).setColor(side);
		vc.addVertex(pose, (float) x1, (float) y2, (float) z1).setColor(side);
	}

	private static void line(VertexConsumer vc, PoseStack.Pose pose,
			double x1, double y1, double z1, double x2, double y2, double z2, int color) {
		float nx = (float) (x2 - x1);
		float ny = (float) (y2 - y1);
		float nz = (float) (z2 - z1);
		float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (length < 1.0e-5f) {
			return;
		}
		nx /= length;
		ny /= length;
		nz /= length;
		vc.addVertex(pose, (float) x1, (float) y1, (float) z1)
				.setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(1.25f);
		vc.addVertex(pose, (float) x2, (float) y2, (float) z2)
				.setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(1.25f);
	}

	private static int withAlpha(int color, int alpha) {
		return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
	}

	private static MultiBufferSource.BufferSource batch() {
		if (batch == null) {
			buffer = new ByteBufferBuilder(2 * 1024 * 1024);
			batch = MultiBufferSource.immediate(buffer);
		}
		return batch;
	}

	public static void reset() {
		CACHES.clear();
		discard();
	}

	private static void discard() {
		if (batch != null) {
			try {
				batch.endBatch();
			} catch (RuntimeException | LinkageError failure) {
				Guard.report("chunk plate flush", failure);
			}
			batch = null;
		}
		if (buffer != null) {
			try {
				buffer.close();
			} catch (RuntimeException | LinkageError failure) {
				Guard.report("chunk plate buffer", failure);
			}
			buffer = null;
		}
	}
}
