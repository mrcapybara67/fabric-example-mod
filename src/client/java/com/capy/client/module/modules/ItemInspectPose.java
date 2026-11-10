package com.capy.client.module.modules;

import com.capy.client.module.ModuleManager;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Hand-space transform for Item Inspect.
 *
 * <p>The pose is pushed in {@code ItemInHandRenderer.renderItem} after the arm
 * has been transformed and before the item's display transform is applied, so
 * these translations are metres from the fist and these rotations turn the
 * item about the grip. Every motion is multiplied by an envelope that is zero
 * on the first and last frame, which is what makes the animation start and end
 * exactly at the normal hand pose.</p>
 */
public final class ItemInspectPose {
	private static final long CANCEL_NANOS = 160_000_000L;
	private static final int INITIAL_STACK = 8;
	private static final int MAX_STACK = 32;

	private static boolean running;
	private static boolean ending;
	private static long startedAt;
	private static long endedAt;
	private static long durationNanos;
	private static float cancelFrom;
	private static float cancelAt;
	private static Item startItem;
	private static ItemInspectModule.Tool tool;

	private static boolean[] pushedByCall = new boolean[INITIAL_STACK];
	private static int depth;

	private ItemInspectPose() {
	}

	public static void start(ItemInspectModule.Tool kind, float speed, ItemStack stack) {
		tool = kind;
		startItem = stack == null ? null : stack.getItem();
		durationNanos = (long) (kind.durationNanos() / Math.max(0.5f, Math.min(2f, speed)));
		startedAt = System.nanoTime();
		ending = false;
		cancelFrom = 0f;
		cancelAt = 0f;
		running = true;
		depth = 0;
	}

	/** Bends the current pose back to rest over 160 ms. */
	public static void cancel() {
		if (!running || ending) {
			return;
		}
		ending = true;
		endedAt = System.nanoTime();
		cancelFrom = rawProgress();
		cancelAt = 0f;
	}

	public static boolean isRunning() {
		return running;
	}

	public static void frame(LocalPlayer player, float partialTick) {
		if (!running) {
			return;
		}
		ItemInspectModule module = ModuleManager.get().get(ItemInspectModule.class);
		if (module == null || !module.isEnabled() || !module.animationEnabled()
				|| player == null || player.isUsingItem()
				|| player.getAttackAnim(partialTick) > 0.01f) {
			cancel();
		}
		if (startItem != null && player != null
				&& player.getMainHandItem().getItem() != startItem) {
			cancel();
		}
		if (rawProgress() >= 1f && !ending) {
			running = false;
			depth = 0;
		}
	}

	public static void begin(PoseStack pose, LivingEntity entity, ItemStack stack,
			ItemDisplayContext context) {
		boolean pushed = false;
		if (running && entity != null && entity == Minecraft.getInstance().player
				&& (context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
						|| context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND)
				&& tool != null && (startItem == null || stack.getItem() == startItem)) {
			float t = progress();
			if (t > 0f && t < 1f) {
				pose.pushPose();
				apply(pose, tool, t,
						context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND);
				pushed = true;
			}
		}
		push(pushed);
	}

	public static void end(PoseStack pose) {
		if (depth == 0) {
			return;
		}
		if (pushedByCall[--depth]) {
			pose.popPose();
		}
	}

	private static float rawProgress() {
		if (!running) {
			return 1f;
		}
		return Math.max(0f, Math.min(1f,
				(System.nanoTime() - startedAt) / (float) durationNanos));
	}

	private static float progress() {
		if (!running) {
			return 1f;
		}
		if (ending) {
			float remain = 1f - (System.nanoTime() - endedAt) / (float) CANCEL_NANOS;
			if (remain <= 0f) {
				running = false;
				ending = false;
				depth = 0;
				return 1f;
			}
			return cancelFrom * Math.max(0f, remain);
		}
		return rawProgress();
	}

	private static void push(boolean pushed) {
		if (depth == MAX_STACK) {
			depth = 0;
		}
		if (depth == pushedByCall.length) {
			boolean[] grown = new boolean[Math.min(MAX_STACK, pushedByCall.length * 2)];
			System.arraycopy(pushedByCall, 0, grown, 0, pushedByCall.length);
			pushedByCall = grown;
		}
		pushedByCall[depth++] = pushed;
	}

	private static void apply(PoseStack pose, ItemInspectModule.Tool kind, float raw, boolean left) {
		float t = smooth(raw);
		float arc = (float) Math.sin(Math.PI * raw);
		float m = left ? -1f : 1f;
		// A tiny lift shared by every animation; the per-tool motion adds its
		// own movement, and the envelope returns both to zero.
		pose.translate(m * 0.018f * arc, 0.030f * arc, -0.075f * arc);
		pose.mulPose(Axis.ZP.rotationDegrees(m * -12.0f * arc));
		switch (kind) {
			case SWORD -> {
				pose.translate(m * 0.025f * arc, 0.055f * arc, -0.055f * arc);
				pose.mulPose(Axis.ZP.rotationDegrees(m * -34.0f * arc));
				pose.mulPose(Axis.XP.rotationDegrees(16.0f * arc));
				pose.mulPose(Axis.YP.rotationDegrees(m * 360.0f * t));
				pose.mulPose(Axis.ZP.rotationDegrees(m * 42.0f * (float) Math.sin(Math.PI * raw)));
			}
			case AXE -> {
				pose.translate(m * 0.010f * arc, 0.095f * arc, -0.020f * arc);
				pose.mulPose(Axis.XP.rotationDegrees(360.0f * smooth(Math.min(1f, raw * 1.03f))));
				pose.mulPose(Axis.ZP.rotationDegrees(m * 18.0f * arc));
				// A small weight bounce when the head comes around.
				pose.translate(0.0f, -0.025f * (float) Math.sin(Math.PI * raw) * arc, 0.0f);
			}
			case PICKAXE -> {
				pose.translate(0.0f, 0.020f * arc, -0.125f * arc);
				pose.translate(0.0f, 0.120f * arc, 0.0f);
				pose.mulPose(Axis.YP.rotationDegrees(m * 360.0f * t));
				pose.mulPose(Axis.XP.rotationDegrees(18.0f * arc));
				// Catch: dip after the toss rather than returning stiffly.
				float catchDip = raw < 0.72f ? 0f : (float) Math.sin(Math.PI * (raw - 0.72f) / 0.28f);
				pose.translate(0.0f, -0.035f * catchDip, 0.0f);
			}
			case SHOVEL, HOE -> {
				pose.translate(m * 0.022f * arc, 0.050f * arc, -0.060f * arc);
				pose.mulPose(Axis.ZP.rotationDegrees(m * 360.0f * t));
				pose.mulPose(Axis.XP.rotationDegrees(14.0f * arc));
			}
		}
	}

	private static float smooth(float t) {
		float c = Math.max(0f, Math.min(1f, t));
		return c * c * (3f - 2f * c);
	}
}
