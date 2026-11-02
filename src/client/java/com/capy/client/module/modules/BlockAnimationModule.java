package com.capy.client.module.modules;

import com.capy.client.config.ConfigValues;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.Category;
import com.capy.client.module.ChoiceSetting;
import com.capy.client.module.ColorSetting;
import com.capy.client.module.Configurable;
import com.capy.client.module.Module;
import com.capy.client.module.SliderSetting;
import com.capy.client.module.Tickable;
import com.capy.client.module.modules.esp.EspRenderTypes;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Block Animation — a shape blooms out of every block you place.
 *
 * <h2>Catching the placement</h2>
 * The use callback runs <em>before</em> the block exists, so the module records
 * where the block is about to go and confirms it on the next client tick. If the
 * client's prediction was refused — a protected area, a desync — the block at
 * that spot is still air and nothing is drawn. That confirmation is the whole
 * difference between "a block was placed" and "the player clicked".
 *
 * <h2>The animation</h2>
 * One animation runs for its duration: {@code progress} drives the easing, the
 * easing drives the scale, and the alpha follows a sine so the shape is
 * invisible at both ends. Ease out is the default because a shape that appears
 * instantly and settles reads as the block being placed, while a shape that
 * eases in reads as something flying at the block.
 *
 * The list of animations is a {@link CopyOnWriteArrayList}: it is written from
 * the tick thread and read from the render thread every frame, and a placement
 * that arrives mid-frame must never resize the list under the loop.
 */
public class BlockAnimationModule extends Module implements Configurable, Tickable {
	private static final int CUBE = 0;
	private static final int SPHERE = 1;
	private static final int FLAT = 2;
	private static final int BUFFER_BYTES = 512 * 1024;
	/** An animation keeps existing this long past its own end, for the ripple. */
	private static final long EXPIRE_MARGIN_MS = 50L;

	/** One placement that is playing. */
	private record BlockAnimation(BlockPos pos, long startTimeMs, long durationMs, Direction face) {
	}

	private final ColorSetting color = setting(new ColorSetting("color", 0xFF88FFCC));
	private final SliderSetting duration = setting(new SliderSetting("duration", 400, 200, 1000));
	private final SliderSetting size = setting(new SliderSetting("size", 12, 5, 30, 1));
	private final SliderSetting opacity = setting(new SliderSetting("opacity", 80, 10, 100, 2));
	private final SliderSetting thickness = setting(new SliderSetting("thickness", 15, 5, 40, 1));
	private final BooleanSetting fill = setting(new BooleanSetting("fill", false));
	private final BooleanSetting ripple = setting(new BooleanSetting("ripple", false));
	private final ChoiceSetting shape = setting(new ChoiceSetting("shape", CUBE, "cube", "sphere", "flat"));
	private final ChoiceSetting easing = setting(new ChoiceSetting("easing", 0,
			"ease_out", "ease_in_out", "bounce"));

	private final CopyOnWriteArrayList<BlockAnimation> activeAnimations = new CopyOnWriteArrayList<>();

	private boolean listening;
	private BlockPos pendingPos;
	private Direction pendingFace = Direction.UP;
	private boolean pending;

	/** Camera position of the frame being drawn, shared by the geometry helpers. */
	private static double camX;
	private static double camY;
	private static double camZ;

	private MultiBufferSource.BufferSource batch;
	private ByteBufferBuilder buffer;

	public BlockAnimationModule() {
		super(Category.VISUAL, "block_animation");
	}

	@Override
	protected void onToggle(boolean state) {
		if (state) {
			if (!listening) {
				WorldRenderEvents.AFTER_ENTITIES.register(this::render);
				UseBlockCallback.EVENT.register(this::onUseBlock);
				listening = true;
			}
			return;
		}
		pending = false;
		activeAnimations.clear();
		dropBatch();
	}

	// ------------------------------------------------------------- placement

	private InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (!isEnabled() || hand != InteractionHand.MAIN_HAND || player != Minecraft.getInstance().player) {
			return InteractionResult.PASS;
		}
		// Only a block item can place a block. Without this, every right click
		// near a wall — a sword swing, a loaf of bread, an empty hand — would be
		// recorded, and the next tick would see the occupied space beside the
		// click and play an animation for a block that was never placed.
		if (!(player.getMainHandItem().getItem() instanceof BlockItem)) {
			return InteractionResult.PASS;
		}
		BlockPos clicked = hit.getBlockPos();
		// A block is placed into the clicked space when that space can be
		// replaced (grass, snow, water), and against its face otherwise.
		pendingPos = level.getBlockState(clicked).canBeReplaced()
				? clicked
				: clicked.relative(hit.getDirection());
		pendingFace = hit.getDirection();
		pending = true;
		return InteractionResult.PASS;
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!pending) {
			return;
		}
		pending = false;
		if (!isEnabled() || mc.level == null || pendingPos == null) {
			return;
		}
		BlockState state = mc.level.getBlockState(pendingPos);
		if (state.isAir()) {
			return;
		}
		// The clock starts here, when the block is confirmed, not when the click
		// was recorded: a placement confirmed a frame or two later would
		// otherwise open part way through its own animation.
		activeAnimations.add(new BlockAnimation(pendingPos, System.currentTimeMillis(),
				duration.get(), pendingFace));
	}

	// ---------------------------------------------------------------- render

	private void render(WorldRenderContext context) {
		if (!isEnabled() || activeAnimations.isEmpty()) {
			return;
		}
		// Switching the module off drops the source, but this listener stays
		// registered for one more frame. A batch whose builder has already been
		// closed would write into freed native memory, so a half dropped pair is
		// reset before anything is drawn.
		if (batch != null && buffer == null) {
			dropBatch();
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return;
		}
		long now = System.currentTimeMillis();
		float rippleFactor = ripple.value() ? 1.7f : 1f;
		activeAnimations.removeIf(animation ->
				animation.startTimeMs() + Math.round(animation.durationMs() * rippleFactor)
						+ EXPIRE_MARGIN_MS < now);
		if (activeAnimations.isEmpty()) {
			return;
		}

		Vec3 camera = mc.gameRenderer.getMainCamera().position();
		camX = camera.x;
		camY = camera.y;
		camZ = camera.z;
		PoseStack stack = context.matrices();
		PoseStack.Pose pose = stack == null ? new PoseStack().last() : stack.last();
		int rgb = color.argb();

		try {
			if (fill.value()) {
				VertexConsumer quads = batch().getBuffer(EspRenderTypes.quads());
				for (BlockAnimation animation : activeAnimations) {
					drawAnimation(quads, pose, animation, now, true, rgb, 0f);
				}
				batch().endBatch();
			}
			VertexConsumer lines = batch().getBuffer(EspRenderTypes.lines());
			float width = Math.max(1f, (float) thickness.real() * mc.getWindow().getGuiScale());
			for (BlockAnimation animation : activeAnimations) {
				drawAnimation(lines, pose, animation, now, false, rgb, width);
			}
			batch().endBatch();
		} catch (RuntimeException e) {
			// A failed draw must cost the animation, not the frame.
			dropBatch();
		}
	}

	private void drawAnimation(VertexConsumer vc, PoseStack.Pose pose, BlockAnimation animation,
			long now, boolean filled, int rgb, float width) {
		float durationMs = Math.max(1f, animation.durationMs());
		long elapsed = now - animation.startTimeMs();
		float progress = clamp01(elapsed / durationMs);
		float eased = ease(progress);
		float peak = (float) size.real();
		float alpha = (float) opacity.real() * (float) Math.sin(progress * Math.PI);

		// A flash of white on the first tenth of a second, blending back into
		// the chosen colour. A placement is an event: the eye catches a bright
		// edge far better than a soft one, and the colour is still the user's
		// by the time the shape is readable.
		float flash = Math.max(0f, 1f - elapsed / 90f);
		int hot = mix(rgb, 0xFFFFFFFF, flash * 0.85f);
		// A small overshoot that decays into the resting size, so the shape
		// settles instead of stopping dead at its final scale.
		float settle = (float) Math.sin(progress * Math.PI * 2.0) * 0.045f * (1f - progress);
		float scale = 1.0f + (peak - 1.0f) * eased + settle;
		drawShape(vc, pose, animation, scale, withAlpha(hot, alpha), filled, width);

		// The shockwave: one ring leaving the block at the speed of the
		// placement, always on. It is the motion that makes the effect read as
		// "this block landed" rather than as a wireframe fading in.
		float wave = clamp01(elapsed / (durationMs * 0.55f));
		if (wave < 1f) {
			float waveScale = scale * (1f + 0.55f * wave);
			int waveColor = withAlpha(hot, alpha * (1f - wave) * 0.55f);
			drawShape(vc, pose, animation, waveScale, waveColor, filled, Math.max(1f, width * (1f - wave)));
		}

		if (!ripple.value()) {
			return;
		}
		float rippleProgress = clamp01((elapsed - durationMs * 0.3f) / (durationMs * 0.7f));
		if (rippleProgress <= 0f || rippleProgress >= 1f) {
			return;
		}
		float rippleAlpha = alpha * 0.45f * (float) Math.sin(rippleProgress * Math.PI);
		drawShape(vc, pose, animation, (1.0f + (peak - 1.0f) * eased) * (1f + rippleProgress * 0.5f),
				withAlpha(rgb, rippleAlpha), filled, width);
	}

	private void drawShape(VertexConsumer vc, PoseStack.Pose pose, BlockAnimation animation, float scale,
			int argb, boolean filled, float width) {
		double cx = animation.pos().getX() + 0.5;
		double cy = animation.pos().getY() + 0.5;
		double cz = animation.pos().getZ() + 0.5;
		switch (shape.index()) {
			case SPHERE -> sphere(vc, pose, cx, cy, cz, 0.866 * scale, argb, filled, width);
			case FLAT -> flat(vc, pose, animation.pos(), animation.face(), scale, argb, filled, width);
			default -> cube(vc, pose, cx, cy, cz, 0.5 * scale, argb, filled, width);
		}
	}

	// -------------------------------------------------------------- geometry

	private void cube(VertexConsumer vc, PoseStack.Pose pose, double cx, double cy, double cz,
			double half, int argb, boolean filled, float width) {
		double x0 = cx - half;
		double x1 = cx + half;
		double y0 = cy - half;
		double y1 = cy + half;
		double z0 = cz - half;
		double z1 = cz + half;
		if (filled) {
			// North, south, west, east, top, bottom.
			face(vc, pose, x0, y0, z0, x1, y1, z1, argb, Direction.NORTH);
			face(vc, pose, x0, y0, z0, x1, y1, z1, argb, Direction.SOUTH);
			face(vc, pose, x0, y0, z0, x1, y1, z1, argb, Direction.WEST);
			face(vc, pose, x0, y0, z0, x1, y1, z1, argb, Direction.EAST);
			face(vc, pose, x0, y0, z0, x1, y1, z1, argb, Direction.UP);
			face(vc, pose, x0, y0, z0, x1, y1, z1, argb, Direction.DOWN);
			return;
		}
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

	/** Three orthogonal circles: a longitude, a latitude and an equator. */
	private void sphere(VertexConsumer vc, PoseStack.Pose pose, double cx, double cy, double cz,
			double radius, int argb, boolean filled, float width) {
		int steps = 32;
		for (int ring = 0; ring < 3; ring++) {
			double px = 0.0;
			double py = 0.0;
			double pz = 0.0;
			for (int i = 0; i <= steps; i++) {
				double angle = i * Math.PI * 2.0 / steps;
				double cos = Math.cos(angle) * radius;
				double sin = Math.sin(angle) * radius;
				double nx;
				double ny;
				double nz;
				if (ring == 0) {
					nx = cos;
					ny = sin;
					nz = 0.0;
				} else if (ring == 1) {
					nx = cos;
					ny = 0.0;
					nz = sin;
				} else {
					nx = 0.0;
					ny = cos;
					nz = sin;
				}
				if (i > 0) {
					if (filled) {
						fanTri(vc, pose, cx, cy, cz, cx + px, cy + py, cz + pz, cx + nx, cy + ny, cz + nz, argb);
					} else {
						line(vc, pose, cx + px, cy + py, cz + pz, cx + nx, cy + ny, cz + nz, argb, width);
					}
				}
				px = nx;
				py = ny;
				pz = nz;
			}
		}
	}

	/** A flat square on the face the block was placed against. */
	private void flat(VertexConsumer vc, PoseStack.Pose pose, BlockPos pos, Direction face, float scale,
			int argb, boolean filled, float width) {
		double half = 0.5 + (scale - 1.0) * 0.5;
		double cx = pos.getX() + 0.5;
		double cy = pos.getY() + 0.5;
		double cz = pos.getZ() + 0.5;
		double x0 = cx - half;
		double x1 = cx + half;
		double y0 = cy - half;
		double y1 = cy + half;
		double z0 = cz - half;
		double z1 = cz + half;
		switch (face) {
			case EAST -> x0 = x1 = pos.getX() + 1.0;
			case WEST -> x0 = x1 = pos.getX();
			case UP -> y0 = y1 = pos.getY() + 1.0;
			case DOWN -> y0 = y1 = pos.getY();
			case SOUTH -> z0 = z1 = pos.getZ() + 1.0;
			case NORTH -> z0 = z1 = pos.getZ();
		}
		boolean flatX = face.getAxis() == Direction.Axis.X;
		boolean flatY = face.getAxis() == Direction.Axis.Y;
		if (filled) {
			if (flatX) {
				quad(vc, pose, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1, argb);
			} else if (flatY) {
				quad(vc, pose, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, argb);
			} else {
				quad(vc, pose, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, argb);
			}
			return;
		}
		if (flatX) {
			line(vc, pose, x0, y0, z0, x0, y1, z0, argb, width);
			line(vc, pose, x0, y1, z0, x0, y1, z1, argb, width);
			line(vc, pose, x0, y1, z1, x0, y0, z1, argb, width);
			line(vc, pose, x0, y0, z1, x0, y0, z0, argb, width);
		} else if (flatY) {
			line(vc, pose, x0, y0, z0, x1, y0, z0, argb, width);
			line(vc, pose, x1, y0, z0, x1, y0, z1, argb, width);
			line(vc, pose, x1, y0, z1, x0, y0, z1, argb, width);
			line(vc, pose, x0, y0, z1, x0, y0, z0, argb, width);
		} else {
			line(vc, pose, x0, y0, z0, x1, y0, z0, argb, width);
			line(vc, pose, x1, y0, z0, x1, y1, z0, argb, width);
			line(vc, pose, x1, y1, z0, x0, y1, z0, argb, width);
			line(vc, pose, x0, y1, z0, x0, y0, z0, argb, width);
		}
	}

	/**
	 * One face of a box, wound so that both sides are visible.
	 *
	 * <p>Each face is shaded by its own normal rather than filled with one flat
	 * colour. A single colour across six faces reads as a silhouette - the
	 * shape has no inside - while four brightness steps are enough for the eye
	 * to place the light and see the box turn as it grows.
	 */
	private static void face(VertexConsumer vc, PoseStack.Pose pose, double x0, double y0, double z0,
			double x1, double y1, double z1, int argb, Direction direction) {
		argb = shade(argb, switch (direction) {
			case UP -> 1.0f;
			case DOWN -> 0.52f;
			case NORTH, SOUTH -> 0.82f;
			case EAST, WEST -> 0.68f;
		});
		switch (direction) {
			case NORTH -> quad(vc, pose, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, argb);
			case SOUTH -> quad(vc, pose, x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1, argb);
			case WEST -> quad(vc, pose, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, argb);
			case EAST -> quad(vc, pose, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, argb);
			case UP -> quad(vc, pose, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, argb);
			case DOWN -> quad(vc, pose, x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1, argb);
		}
	}

	private static void quad(VertexConsumer vc, PoseStack.Pose pose,
			double x0, double y0, double z0, double x1, double y1, double z1,
			double x2, double y2, double z2, double x3, double y3, double z3, int argb) {
		vertex(vc, pose, x0, y0, z0, argb);
		vertex(vc, pose, x1, y1, z1, argb);
		vertex(vc, pose, x2, y2, z2, argb);
		vertex(vc, pose, x3, y3, z3, argb);
	}

	/** A triangle written as a quad with its last vertex repeated. */
	private static void fanTri(VertexConsumer vc, PoseStack.Pose pose,
			double ax, double ay, double az, double bx, double by, double bz,
			double cx, double cy, double cz, int argb) {
		vertex(vc, pose, ax, ay, az, argb);
		vertex(vc, pose, bx, by, bz, argb);
		vertex(vc, pose, cx, cy, cz, argb);
		vertex(vc, pose, cx, cy, cz, argb);
	}

	private static void vertex(VertexConsumer vc, PoseStack.Pose pose, double x, double y, double z, int argb) {
		vc.addVertex(pose, (float) (x - camX), (float) (y - camY), (float) (z - camZ)).setColor(argb);
	}

	private static void line(VertexConsumer vc, PoseStack.Pose pose,
			double x1, double y1, double z1, double x2, double y2, double z2, int argb, float width) {
		double dx = x2 - x1;
		double dy = y2 - y1;
		double dz = z2 - z1;
		double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (length < 1.0e-5) {
			return;
		}
		float nx = (float) (dx / length);
		float ny = (float) (dy / length);
		float nz = (float) (dz / length);
		vc.addVertex(pose, (float) (x1 - camX), (float) (y1 - camY), (float) (z1 - camZ))
				.setColor(argb).setNormal(pose, nx, ny, nz).setLineWidth(width);
		vc.addVertex(pose, (float) (x2 - camX), (float) (y2 - camY), (float) (z2 - camZ))
				.setColor(argb).setNormal(pose, nx, ny, nz).setLineWidth(width);
	}

	// ------------------------------------------------------------------ maths

	private float ease(float progress) {
		return switch (easing.option()) {
			case "ease_in_out" -> progress < 0.5f
					? 4f * progress * progress * progress
					: 1f - (float) Math.pow(-2f * progress + 2f, 3) / 2f;
			case "bounce" -> bounce(progress);
			default -> 1f - (float) Math.pow(1f - progress, 3);
		};
	}

	/** The standard bounce curve: four increasingly small hops. */
	private static float bounce(float progress) {
		float n = 7.5625f;
		float d = 2.75f;
		if (progress < 1f / d) {
			return n * progress * progress;
		}
		if (progress < 2f / d) {
			float p = progress - 1.5f / d;
			return n * p * p + 0.75f;
		}
		if (progress < 2.5f / d) {
			float p = progress - 2.25f / d;
			return n * p * p + 0.9375f;
		}
		float p = progress - 2.625f / d;
		return n * p * p + 0.984375f;
	}

	private static float clamp01(double value) {
		return (float) Math.max(0.0, Math.min(1.0, value));
	}

	/** Multiplies the colour channels by {@code factor}, leaving alpha alone. */
	private static int shade(int argb, float factor) {
		float f = Math.max(0.2f, Math.min(1.2f, factor));
		int a = (argb >>> 24) & 0xFF;
		int r = Math.min(255, Math.round(((argb >>> 16) & 0xFF) * f));
		int g = Math.min(255, Math.round(((argb >>> 8) & 0xFF) * f));
		int b = Math.min(255, Math.round((argb & 0xFF) * f));
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	/** Linear blend of two ARGB colours, alpha included. */
	private static int mix(int from, int to, float t) {
		float f = Math.max(0f, Math.min(1f, t));
		int a = Math.round(((from >>> 24) & 0xFF) * (1f - f) + ((to >>> 24) & 0xFF) * f);
		int r = Math.round(((from >>> 16) & 0xFF) * (1f - f) + ((to >>> 16) & 0xFF) * f);
		int g = Math.round(((from >>> 8) & 0xFF) * (1f - f) + ((to >>> 8) & 0xFF) * f);
		int b = Math.round((from & 0xFF) * (1f - f) + (to & 0xFF) * f);
		return (a << 24) | (r << 16) | (g << 8) | b;
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
		color.set(ConfigValues.intValue(json, "color", 0xFF88FFCC));
		duration.set(ConfigValues.intValue(json, "duration", 400));
		size.set(ConfigValues.intValue(json, "size", 12));
		opacity.set(ConfigValues.intValue(json, "opacity", 80));
		thickness.set(ConfigValues.intValue(json, "thickness", 15));
		fill.set(ConfigValues.boolValue(json, "fill", false));
		ripple.set(ConfigValues.boolValue(json, "ripple", false));
		shape.select(ConfigValues.stringValue(json, "shape", shape.option()));
		easing.select(ConfigValues.stringValue(json, "easing", easing.option()));
	}

	@Override
	public void saveConfig(JsonObject json) {
		json.addProperty("color", color.argb());
		json.addProperty("duration", duration.get());
		json.addProperty("size", size.get());
		json.addProperty("opacity", opacity.get());
		json.addProperty("thickness", thickness.get());
		json.addProperty("fill", fill.value());
		json.addProperty("ripple", ripple.value());
		json.addProperty("shape", shape.option());
		json.addProperty("easing", easing.option());
	}
}
