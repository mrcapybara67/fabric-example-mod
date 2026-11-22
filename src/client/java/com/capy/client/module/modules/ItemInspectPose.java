package com.capy.client.module.modules;

import com.capy.client.module.ModuleManager;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.concurrent.ThreadLocalRandom;
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
 * <p>Each press chooses one of three rotations: a two-sided side inspection,
 * a full grip-axis turn, or a cross-axis flip. The variants are deliberately
 * rotations rather than separate gimmicks, which is what keeps a sword, an
 * axe and a pickaxe reading like the same inspection language. The variant is
 * chosen when the inspect starts and is not configurable.</p>
 */
public final class ItemInspectPose {
	private static final long CANCEL_NANOS = 180_000_000L;
	private static final int INITIAL_STACK = 8;
	private static final int MAX_STACK = 32;

	/** The three inspect motions every weapon profile can play. */
	enum Variant {
		/** Turn the item side-on, hold it, then return. */
		SIDE(1.00f),
		/** One full turn around the grip axis, with a long ease. */
		TURN(1.06f),
		/** A quicker cross-axis flip that settles back into the hand. */
		FLIP(0.94f);

		private final float durationScale;

		Variant(float durationScale) {
			this.durationScale = durationScale;
		}

		float durationScale() {
			return durationScale;
		}
	}

	/**
	 * One inspection profile. Yaw values are the two sides the item is turned
	 * to; roll values control the blade/head angle; pitch lifts the business
	 * end toward the eye. {@code spinDegrees} is the direction and size of a
	 * full turn; {@code spinRoll} chooses Z instead of Y for the TURN variant.
	 * The units are degrees, except the translations, which are metres in hand
	 * space.
	 */
	private record Profile(float liftX, float liftY, float pull,
			float yawA, float yawB, float rollA, float rollB, float pitch, float flick,
			float spinDegrees, boolean spinRoll) {
	}

	private static final Profile SWORD_PROFILE = new Profile(
			0.022f, 0.078f, 0.120f, 50f, -30f, -18f, 20f, -60f, 10f, 360f, false);
	private static final Profile SPEAR_PROFILE = new Profile(
			0.018f, 0.070f, 0.135f, 64f, -36f, 18f, -12f, -74f, 8f, -360f, false);
	private static final Profile AXE_PROFILE = new Profile(
			0.018f, 0.080f, 0.105f, 44f, -24f, -12f, 16f, -42f, 8f, -360f, true);
	private static final Profile PICKAXE_PROFILE = new Profile(
			0.018f, 0.078f, 0.105f, 48f, -26f, -10f, 14f, -38f, 8f, 360f, false);
	private static final Profile SHOVEL_PROFILE = new Profile(
			0.018f, 0.072f, 0.100f, 42f, -22f, -8f, 12f, -30f, 6f, -360f, false);
	private static final Profile HOE_PROFILE = new Profile(
			0.017f, 0.070f, 0.098f, 38f, -18f, -6f, 10f, -26f, 5f, 360f, false);
	private static final Profile MACE_PROFILE = new Profile(
			0.020f, 0.082f, 0.110f, 48f, -26f, -14f, 16f, -48f, 9f, -360f, false);
	private static final Profile RANGED_PROFILE = new Profile(
			0.018f, 0.070f, 0.095f, 40f, -20f, -10f, 12f, -24f, 6f, 360f, false);
	private static final Profile GENERIC_PROFILE = new Profile(
			0.020f, 0.076f, 0.108f, 46f, -24f, -14f, 16f, -50f, 8f, -360f, false);

	private static boolean running;
	private static boolean ending;
	private static boolean leftHanded;
	private static long startedAt;
	private static long endedAt;
	private static long durationNanos;
	private static float cancelFrom;
	private static Item startItem;
	private static ItemStack startStack;
	private static ItemInspectModule.Tool tool;
	private static Variant variant = Variant.SIDE;
	private static Variant lastVariant;

	private static boolean[] pushedByCall = new boolean[INITIAL_STACK];
	private static int depth;

	private ItemInspectPose() {
	}

	public static void start(ItemInspectModule.Tool kind, ItemStack stack) {
		start(kind, stack, nextVariant());
	}

	/** Seeded entry point for tests and deterministic replays. */
	static void start(ItemInspectModule.Tool kind, ItemStack stack, Variant selected) {
		if (kind == null || stack == null || stack.isEmpty() || selected == null) {
			cancel();
			return;
		}
		tool = kind;
		variant = selected;
		lastVariant = selected;
		startItem = stack.getItem();
		startStack = stack;
		durationNanos = (long) (kind.durationNanos() * selected.durationScale());
		startedAt = System.nanoTime();
		ending = false;
		cancelFrom = 0f;
		running = true;
		depth = 0;
		Minecraft mc = Minecraft.getInstance();
		leftHanded = mc != null && mc.options != null
				&& mc.options.mainHand().get() == HumanoidArm.LEFT;
	}

	/**
	 * Pick a different variant from the previous press. It is still random,
	 * but the same flourish never plays twice in a row, which makes pressing C
	 * feel like a rotation instead of a coin toss that can repeat.
	 */
	private static Variant nextVariant() {
		Variant[] all = Variant.values();
		int index = ThreadLocalRandom.current().nextInt(all.length);
		if (all.length > 1 && all[index] == lastVariant) {
			index = (index + 1 + ThreadLocalRandom.current().nextInt(all.length - 1)) % all.length;
		}
		return all[index];
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
		if (module == null || !module.isEnabled()
				|| player == null || player.isUsingItem()
				|| player.getAttackAnim(partialTick) > 0.01f) {
			cancel();
		}
		// A slot change between two copies of the same item keeps the Item
		// equal, so the old check missed it and the flourish jumped onto the
		// new stack. The selected stack is a different object per slot.
		ItemStack held = player == null ? null : player.getMainHandItem();
		if (startItem != null && (held == null || held != startStack || held.getItem() != startItem)) {
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
				// The selected stack, not merely the same item type. A slot
				// change between two copies must not inherit the old flourish.
				&& (startItem == null || (stack == startStack && stack.getItem() == startItem))) {
			float t = progress();
			if (t > 0f && t < 1f) {
				pose.pushPose();
				apply(pose, tool, variant, t);
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

	public static void applyForTest(PoseStack pose, ItemInspectModule.Tool kind,
			Variant selected, float raw, boolean left) {
		apply(pose, kind, selected, Math.max(0f, Math.min(1f, raw)), left);
	}

	/** Package-private hook for checking the rotation without a renderer. */
	static float spinForTest(ItemInspectModule.Tool kind, Variant selected, float raw) {
		return spinAngle(kind, selected, Math.max(0f, Math.min(1f, raw)));
	}

	private static void apply(PoseStack pose, ItemInspectModule.Tool kind,
			Variant selected, float raw) {
		apply(pose, kind, selected, raw, leftHanded);
	}

	private static void apply(PoseStack pose, ItemInspectModule.Tool kind,
			Variant selected, float raw, boolean left) {
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
		float reveal = smooth(segment(raw, 0.08f, 0.28f));
		float settle = 1f - smooth(segment(raw, 0.80f, 1.00f));
		float held = reveal * settle;

		// The side variant has its own two-position turn. The full-turn
		// variants keep one of those sides as their base pose and rotate from
		// there, so all three read as different camera moves on the same item.
		float sideTurn = smooth(segment(raw, 0.40f, 0.64f));
		float flick = (float) Math.sin(Math.PI * segment(raw, 0.60f, 0.82f));
		float breathe = (float) Math.sin(Math.PI * segment(raw, 0.20f, 0.76f));
		float spin = spinAngle(kind, selected, raw);

		float yawA = p.yawA() * held;
		float yawB = p.yawB() * held;
		float rollA = p.rollA() * held;
		float rollB = p.rollB() * held;
		float pitch = p.pitch() * held;

		pose.translate(m * p.liftX() * presence,
				p.liftY() * presence + 0.003f * breathe * held,
				-p.pull() * presence);
		pose.translate(0f, -0.0035f * flick, 0f);

		switch (selected) {
			case SIDE -> {
				pose.mulPose(Axis.YP.rotationDegrees(m * lerp(yawA, yawB, sideTurn)));
				pose.mulPose(Axis.XP.rotationDegrees(pitch));
				pose.mulPose(Axis.ZP.rotationDegrees(m * (lerp(rollA, rollB, sideTurn)
						+ p.flick() * flick)));
			}
			case TURN -> {
				if (p.spinRoll()) {
					pose.mulPose(Axis.YP.rotationDegrees(m * yawA));
					pose.mulPose(Axis.XP.rotationDegrees(pitch));
					pose.mulPose(Axis.ZP.rotationDegrees(m * (rollA + spin)));
				} else {
					pose.mulPose(Axis.YP.rotationDegrees(m * (yawA + spin)));
					pose.mulPose(Axis.XP.rotationDegrees(pitch));
					pose.mulPose(Axis.ZP.rotationDegrees(m * rollA));
				}
			}
			case FLIP -> {
				pose.mulPose(Axis.YP.rotationDegrees(m * yawB));
				pose.mulPose(Axis.XP.rotationDegrees(pitch));
				pose.mulPose(Axis.ZP.rotationDegrees(m * rollB));
				pose.mulPose(Axis.XP.rotationDegrees(m * spin));
			}
		}
	}

	/**
	 * The amount of full turn for the current variant. TURN uses the profile's
	 * signed direction around its grip axis; FLIP uses the opposite direction
	 * around a cross axis. Both snap to zero at their end boundary, rather than
	 * carrying a 360-degree float residue into the resting pose.
	 */
	private static float spinAngle(ItemInspectModule.Tool kind, Variant selected, float raw) {
		Profile p = profile(kind);
		return switch (selected) {
			case SIDE -> 0f;
			case TURN -> raw >= 0.72f ? 0f
					: p.spinDegrees() * smoother(segment(raw, 0.30f, 0.70f));
			case FLIP -> raw >= 0.64f ? 0f
					: -p.spinDegrees() * smoother(segment(raw, 0.38f, 0.62f));
		};
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

	private static float smoother(float t) {
		float c = Math.max(0f, Math.min(1f, t));
		return c * c * c * (c * (c * 6f - 15f) + 10f);
	}
}
