package com.capy.client.module.modules;

import com.capy.client.config.ConfigValues;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.Category;
import com.capy.client.module.ChoiceSetting;
import com.capy.client.module.ColorSetting;
import com.capy.client.module.Configurable;
import com.capy.client.module.Module;
import com.capy.client.module.SliderSetting;
import com.capy.client.module.modules.esp.EspRenderTypes;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Block Overlay — a bracket frame around the block the crosshair is on.
 *
 * The block's own outline shape is used, not a unit cube, so a slab, a stair or
 * a fence is framed where it actually is. Brackets are the corners of that shape:
 * three short arms run inwards along the three axes from each of its eight
 * corners. A whole outline tells you which block; a corner frame tells you where
 * the block ends without covering the face you are about to place against.
 *
 * The frame is drawn with the see-through line type the ESP overlays use, so it
 * is visible through walls where the crosshair can reach — which is the one case
 * this overlay is for: a block you are aiming at and cannot see the edges of.
 * Depth writes stay off, so the frame never clips anything drawn after it.
 */
public class BlockOverlayModule extends Module implements Configurable {
	private static final int CORNERS = 0;
	private static final int FULL_OUTLINE = 1;
	private static final int FACE_CROSS = 2;
	private static final int BUFFER_BYTES = 256 * 1024;

	private final ColorSetting color = setting(new ColorSetting("color", 0xFFFFFFFF));
	private final SliderSetting thickness = setting(new SliderSetting("thickness", 15, 5, 50, 1));
	private final SliderSetting length = setting(new SliderSetting("length", 20, 5, 50, 2));
	private final SliderSetting opacity = setting(new SliderSetting("opacity", 85, 0, 100, 2));
	private final BooleanSetting pulse = setting(new BooleanSetting("pulse", true));
	private final ChoiceSetting shape = setting(new ChoiceSetting("shape", CORNERS,
			"corners", "full_outline", "face_cross"));

	private boolean listening;
	/** Pulse clock in seconds, so one full breath is two seconds. */
	private float time;
	private long lastNanos;

	private MultiBufferSource.BufferSource batch;
	private ByteBufferBuilder buffer;

	public BlockOverlayModule() {
		super(Category.VISUAL, "block_overlay");
	}

	@Override
	protected void onToggle(boolean state) {
		if (state) {
			if (!listening) {
				WorldRenderEvents.AFTER_ENTITIES.register(this::render);
				listening = true;
			}
			lastNanos = 0L;
			return;
		}
		lastNanos = 0L;
		dropBatch();
	}

	// ---------------------------------------------------------------- render

	private void render(WorldRenderContext context) {
		if (!isEnabled()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || !(mc.hitResult instanceof BlockHitResult hit)) {
			return;
		}
		BlockPos pos = hit.getBlockPos();
		BlockState state = mc.level.getBlockState(pos);
		if (state.isAir()) {
			return;
		}

		long now = System.nanoTime();
		float dt = lastNanos == 0L ? 1f / 60f : (now - lastNanos) / 1.0e9f;
		lastNanos = now;
		time += Math.max(0f, Math.min(0.1f, dt));

		// Pulse: 70% to 100% alpha, one full cycle every two seconds.
		float pulseAlpha = pulse.value() ? (float) Math.sin(time * Math.PI) * 0.3f + 0.7f : 1f;
		float alpha = (float) opacity.real() * pulseAlpha;
		int argb = withAlpha(color.argb(), alpha);
		if (((argb >>> 24) & 0xFF) == 0) {
			return;
		}

		Vec3 camera = mc.gameRenderer.getMainCamera().position();
		double camX = camera.x;
		double camY = camera.y;
		double camZ = camera.z;
		AABB box = state.getShape(mc.level, pos).bounds()
				.move(pos.getX(), pos.getY(), pos.getZ());
		if (box.getXsize() <= 0.0 || box.getYsize() <= 0.0 || box.getZsize() <= 0.0) {
			return;
		}

		PoseStack stack = context.matrices();
		PoseStack.Pose pose = stack == null ? new PoseStack().last() : stack.last();
		float width = Math.max(1f, (float) thickness.real() * mc.getWindow().getGuiScale());

		try {
			VertexConsumer lines = batch().getBuffer(EspRenderTypes.lines());
			switch (shape.index()) {
				case FULL_OUTLINE -> outline(lines, pose, box, camX, camY, camZ, argb, width);
				case FACE_CROSS -> faceCross(lines, pose, box, hit.getDirection(), camX, camY, camZ, argb, width);
				default -> corners(lines, pose, box, camX, camY, camZ, argb, width);
			}
			batch().endBatch();
		} catch (RuntimeException e) {
			// A failed draw must cost the overlay, not the frame.
			dropBatch();
		}
	}

	/** Three inward arms from each of the eight corners: twenty-four segments. */
	private void corners(VertexConsumer vc, PoseStack.Pose pose, AABB box,
			double camX, double camY, double camZ, int argb, float width) {
		float armX = (float) (box.getXsize() * length.real());
		float armY = (float) (box.getYsize() * length.real());
		float armZ = (float) (box.getZsize() * length.real());
		float x0 = (float) (box.minX - camX);
		float x1 = (float) (box.maxX - camX);
		float y0 = (float) (box.minY - camY);
		float y1 = (float) (box.maxY - camY);
		float z0 = (float) (box.minZ - camZ);
		float z1 = (float) (box.maxZ - camZ);
		for (int corner = 0; corner < 8; corner++) {
			boolean highX = (corner & 1) != 0;
			boolean highY = (corner & 2) != 0;
			boolean highZ = (corner & 4) != 0;
			float cx = highX ? x1 : x0;
			float cy = highY ? y1 : y0;
			float cz = highZ ? z1 : z0;
			line(vc, pose, cx, cy, cz, cx + (highX ? -armX : armX), cy, cz, argb, width);
			line(vc, pose, cx, cy, cz, cx, cy + (highY ? -armY : armY), cz, argb, width);
			line(vc, pose, cx, cy, cz, cx, cy, cz + (highZ ? -armZ : armZ), argb, width);
		}
	}

	/** The twelve edges of the shape, whole. */
	private void outline(VertexConsumer vc, PoseStack.Pose pose, AABB box,
			double camX, double camY, double camZ, int argb, float width) {
		float x0 = (float) (box.minX - camX);
		float x1 = (float) (box.maxX - camX);
		float y0 = (float) (box.minY - camY);
		float y1 = (float) (box.maxY - camY);
		float z0 = (float) (box.minZ - camZ);
		float z1 = (float) (box.maxZ - camZ);
		line(vc, pose, x0, y0, z0, x1, y0, z0, argb, width);
		line(vc, pose, x1, y0, z0, x1, y0, z1, argb, width);
		line(vc, pose, x1, y0, z1, x0, y0, z1, argb, width);
		line(vc, pose, x0, y0, z1, x0, y0, z0, argb, width);
		line(vc, pose, x0, y1, z0, x1, y1, z0, argb, width);
		line(vc, pose, x1, y1, z0, x1, y1, z1, argb, width);
		line(vc, pose, x1, y1, z1, x0, y1, z1, argb, width);
		line(vc, pose, x0, y1, z1, x0, y1, z0, argb, width);
		line(vc, pose, x0, y0, z0, x0, y1, z0, argb, width);
		line(vc, pose, x1, y0, z0, x1, y1, z0, argb, width);
		line(vc, pose, x1, y0, z1, x1, y1, z1, argb, width);
		line(vc, pose, x0, y0, z1, x0, y1, z1, argb, width);
	}

	/** A cross in the middle of the face the crosshair is on. */
	private void faceCross(VertexConsumer vc, PoseStack.Pose pose, AABB box, Direction face,
			double camX, double camY, double camZ, int argb, float width) {
		float cx = (float) ((box.minX + box.maxX) * 0.5 - camX);
		float cy = (float) ((box.minY + box.maxY) * 0.5 - camY);
		float cz = (float) ((box.minZ + box.maxZ) * 0.5 - camZ);
		float half = 0.2f;
		switch (face) {
			case EAST -> cx = (float) (box.maxX - camX);
			case WEST -> cx = (float) (box.minX - camX);
			case UP -> cy = (float) (box.maxY - camY);
			case DOWN -> cy = (float) (box.minY - camY);
			case SOUTH -> cz = (float) (box.maxZ - camZ);
			case NORTH -> cz = (float) (box.minZ - camZ);
		}
		switch (face.getAxis()) {
			case X -> {
				line(vc, pose, cx, cy - half, cz, cx, cy + half, cz, argb, width);
				line(vc, pose, cx, cy, cz - half, cx, cy, cz + half, argb, width);
			}
			case Y -> {
				line(vc, pose, cx - half, cy, cz, cx + half, cy, cz, argb, width);
				line(vc, pose, cx, cy, cz - half, cx, cy, cz + half, argb, width);
			}
			default -> {
				line(vc, pose, cx - half, cy, cz, cx + half, cy, cz, argb, width);
				line(vc, pose, cx, cy - half, cz, cx, cy + half, cz, argb, width);
			}
		}
	}

	/**
	 * One segment. The normal is the segment's own direction: the line shader
	 * needs it to expand the segment into a screen-space quad of the requested
	 * width.
	 */
	private static void line(VertexConsumer vc, PoseStack.Pose pose,
			float x1, float y1, float z1, float x2, float y2, float z2, int argb, float width) {
		float nx = x2 - x1;
		float ny = y2 - y1;
		float nz = z2 - z1;
		float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (length < 1.0e-5f) {
			return;
		}
		nx /= length;
		ny /= length;
		nz /= length;
		vc.addVertex(pose, x1, y1, z1).setColor(argb).setNormal(pose, nx, ny, nz).setLineWidth(width);
		vc.addVertex(pose, x2, y2, z2).setColor(argb).setNormal(pose, nx, ny, nz).setLineWidth(width);
	}

	private static int withAlpha(int argb, float factor) {
		int alpha = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, factor)));
		return (alpha << 24) | (argb & 0xFFFFFF);
	}

	private MultiBufferSource.BufferSource batch() {
		if (batch == null) {
			buffer = new ByteBufferBuilder(BUFFER_BYTES);
			batch = MultiBufferSource.immediate(buffer);
		}
		return batch;
	}

	private void dropBatch() {
		if (batch != null) {
			try {
				batch.endBatch();
			} catch (RuntimeException ignored) {
				// Already unusable; it is dropped either way.
			}
			batch = null;
		}
		if (buffer != null) {
			try {
				buffer.close();
			} catch (RuntimeException ignored) {
				// Nothing left to release.
			}
			buffer = null;
		}
	}

	// ---------------------------------------------------------- persistence

	@Override
	public void loadConfig(JsonObject json) {
		color.set(ConfigValues.intValue(json, "color", 0xFFFFFFFF));
		thickness.set(ConfigValues.intValue(json, "thickness", 15));
		length.set(ConfigValues.intValue(json, "length", 20));
		opacity.set(ConfigValues.intValue(json, "opacity", 85));
		pulse.set(ConfigValues.boolValue(json, "pulse", true));
		shape.select(ConfigValues.stringValue(json, "shape", shape.option()));
	}

	@Override
	public void saveConfig(JsonObject json) {
		json.addProperty("color", color.argb());
		json.addProperty("thickness", thickness.get());
		json.addProperty("length", length.get());
		json.addProperty("opacity", opacity.get());
		json.addProperty("pulse", pulse.value());
		json.addProperty("shape", shape.option());
	}
}
