package com.capy.client.module.modules;

import com.capy.client.module.ModuleManager;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.HumanoidArm;
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
 *
 * <p>The sequence is deliberately staged like a weapon inspection rather than
 * a spin: lift the item into view, turn it side-on, examine one side, roll to
 * the other side, add a short wrist flick, then settle back into the hand.
 * Nothing loops and nothing snaps; a cancel simply runs the same pose backwards
 * over 180 ms.</p>
 */
public final class ItemInspectPose {
	private static final long CANCEL_NANOS = 180_000_000L;
	private static final int INITIAL_STACK = 8;
	private static final int MAX_STACK = 32;

	/**
	 * One inspection profile. Yaw values are the two sides the item is turned
	 * to; roll values control the blade/head angle; pitch lifts the business
	 * end toward the eye. The units are degrees, except the translations,
	 * which are metres in hand space.
	 */
	private record Profile(float liftX, float liftY, float pull,
			float yawA, float yawB, float rollA, float rollB, float pitch, float flick) {
	}

	private static final Profile SWORD_PROFILE = new Profile(
			0.022f, 0.078f, 0.120f, 50f, -30f, -18f, 20f, -60f, 10f);
	private static final Profile SPEAR_PROFILE = new Profile(
			0.018f, 0.070f, 0.135f, 64f, -36f, 18f, -12f, -74f, 8f);
	private static final Profile AXE_PROFILE = new Profile(
			0.018f, 0.080f, 0.105f, 44f, -24f, -12f, 16f, -42f, 8f);
	private static final Profile PICKAXE_PROFILE = new Profile(
			0.018f, 0.078f, 0.105f, 48f, -26f, -10f, 14f, -38f, 8f);
	private static final Profile SHOVEL_PROFILE = new Profile(
			0.018f, 0.072f, 0.100f, 42f, -22f, -8f, 12f, -30f, 6f);
	private static final Profile HOE_PROFILE = new Profile(
			0.017f, 0.070f, 0.098f, 38f, -18f, -6f, 10f, -26f, 5f);
	private static final Profile MACE_PROFILE = new Profile(
			0.020f, 0.082f, 0.110f, 48f, -26f, -14f, 16f, -48f, 9f);
	private static final Profile RANGED_PROFILE = new Profile(
			0.018f, 0.070f, 0.095f, 40f, -20f, -10f, 12f, -24f, 6f);
	private static final Profile GENERIC_PROFILE = new Profile(
			0.020f, 0.076f, 0.108f, 46f, -24f, -14f, 16f, -50f, 8f);

	private static boolean running;
	private static boolean ending;
	private static boolean leftHanded;
	private static long startedAt;
	private static long endedAt;
	private static long durationNanos;
	private static float cancelFrom;
	private static Item startItem;
	private static ItemInspectModule.Tool tool;

	private static boolean[] pushedByCall = new boolean[INITIAL_STACK];
	private static int depth;

	private ItemInspectPose() {
	}

	public static void start(ItemInspectModule.Tool kind, float speed, ItemStack stack) {
		if (kind == null || stack == null || stack.isEmpty()) {
			cancel();
			return;
		}
		tool = kind;
		startItem = stack.getItem();
		durationNanos = (long) (kind.durationNanos() / Math.max(0.5f, Math.min(2f, speed)));
		startedAt = System.nanoTime();
		ending = false;
		cancelFrom = 0f;
		running = true;
		depth = 0;
		Minecraft mc = Minecraft.getInstance();
		leftHanded = mc != null && mc.options != null
				&& mc.options.mainHand().get() == HumanoidArm.LEFT;
	}

	/** Bends the current pose back to rest over 180 ms. */
	public static void cancel() {
		if (!running || ending) {
			return;
		}
		ending = true;
		endedAt = System.nanoTime();
		cancelFrom = rawProgress();
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
		boolean mainContext = context == (leftHanded
				? ItemDisplayContext.FIRST_PERSON_LEFT_HAND
				: ItemDisplayContext.FIRST_PERSON_RIGHT_HAND);
		if (running && entity != null && entity == Minecraft.getInstance().player
				&& mainContext && tool != null
				&& (startItem == null || stack.getItem() == startItem)) {
			float t = progress();
			if (t > 0f && t < 1f) {
				pose.pushPose();
				apply(pose, tool, t);
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

	public static void applyForTest(PoseStack pose, ItemInspectModule.Tool kind, float raw, boolean left) {
		apply(pose, kind, Math.max(0f, Math.min(1f, raw)), left);
	}

	private static void apply(PoseStack pose, ItemInspectModule.Tool kind, float raw) {
		apply(pose, kind, raw, leftHanded);
	}

	private static void apply(PoseStack pose, ItemInspectModule.Tool kind, float raw, boolean left) {
		// Keep the first and last frames bit-exact. The sine envelopes and the
		// JOML axis rotations are otherwise capable of leaving a tiny residual
		// at sin(pi), which is visible as a one-frame twitch when the item is
		// handed back to the normal renderer.
		if (raw <= 0f || raw >= 1f) {
			return;
		}
		Profile p = profile(kind);
		float m = left ? -1f : 1f;

		// The item is lifted into frame and returned by two overlapping
		// envelopes. Both sides of both envelopes reach zero, so the first and
		// last frames are exactly the normal hand pose.
		float raise = smooth(segment(raw, 0.00f, 0.16f));
		float lower = 1f - smooth(segment(raw, 0.84f, 1.00f));
		float presence = raise * lower;
		float reveal = smooth(segment(raw, 0.10f, 0.32f));
		float turn = smooth(segment(raw, 0.44f, 0.64f));
		float settle = 1f - smooth(segment(raw, 0.80f, 1.00f));
		float held = reveal * settle;

		float yaw = lerp(p.yawA(), p.yawB(), turn) * held;
		float roll = lerp(p.rollA(), p.rollB(), turn) * held;
		float pitch = p.pitch() * held;
		// A short window centred at 0.72: the wrist gives the item one last
		// small roll before it returns, which is what makes the motion read as
		// an inspect rather than a static turn.
		float flick = (float) Math.sin(Math.PI * segment(raw, 0.62f, 0.82f));
		// A slow bob while the item is held up. It is deliberately tiny: the
		// item should feel held, not float.
		float breathe = (float) Math.sin(Math.PI * segment(raw, 0.30f, 0.72f));

		pose.translate(m * p.liftX() * presence,
				p.liftY() * presence + 0.003f * breathe * held,
				-p.pull() * presence);
		pose.translate(0f, -0.0035f * flick, 0f);
		pose.mulPose(Axis.YP.rotationDegrees(m * yaw));
		pose.mulPose(Axis.XP.rotationDegrees(pitch));
		pose.mulPose(Axis.ZP.rotationDegrees(m * (roll + p.flick() * flick)));
	}

	private static Profile profile(ItemInspectModule.Tool kind) {
		return switch (kind) {
			case SWORD -> SWORD_PROFILE;
			case SPEAR -> SPEAR_PROFILE;
			case AXE -> AXE_PROFILE;
			case PICKAXE -> PICKAXE_PROFILE;
			case SHOVEL -> SHOVEL_PROFILE;
			case HOE -> HOE_PROFILE;
			case MACE -> MACE_PROFILE;
			case RANGED -> RANGED_PROFILE;
			case GENERIC -> GENERIC_PROFILE;
		};
	}

	private static float segment(float value, float start, float end) {
		if (end <= start) {
			return value >= end ? 1f : 0f;
		}
		return Math.max(0f, Math.min(1f, (value - start) / (end - start)));
	}

	private static float lerp(float from, float to, float t) {
		return from + (to - from) * t;
	}

	private static float smooth(float t) {
		float c = Math.max(0f, Math.min(1f, t));
		return c * c * (3f - 2f * c);
	}
}
