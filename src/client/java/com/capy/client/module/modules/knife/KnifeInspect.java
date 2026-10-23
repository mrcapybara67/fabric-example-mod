package com.capy.client.module.modules.knife;

import com.capy.client.module.ModuleManager;
import com.capy.client.module.modules.CustomKnifeModule;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * The inspect flourish: a short, hand animated display of the knife the player
 * is holding.
 *
 * Design constraints, and why they are what they are:
 *
 *  - The animation is applied to the first person transform, so it is pure
 *    presentation. The server never sees it, and no other player's view of the
 *    held item is affected.
 *  - Every sequence starts and ends at the identity transform. A flourish that
 *    ended on a rotated pose would leave the knife crooked in the hand until
 *    the next animation, which looks like a bug even when it is not. This is
 *    why the shared motions are multiplied by {@code sin(pi * t)} and the
 *    spins are whole turns: at {@code t = 1} every term is exactly zero or a
 *    multiple of 360 degrees.
 *  - Each style gets its own motion and its own length: a balisong fans, a
 *    karambit hooks over, a cleaver flips end over end, a dagger spins on the
 *    finger, a tanto draws and slashes, a kukri turns twice, a machete takes a
 *    heavy chop and a stiletto punches forward. One animation for every knife
 *    would be cheaper and much worse.
 *  - Timing is wall clock, not ticks, so the motion is smooth at any frame rate
 *    and does not stutter when the client ticks irregularly.
 */
public final class KnifeInspect {
	/** Default length of a flourish, in nanoseconds. */
	private static final long DEFAULT_DURATION_NS = 1_100_000_000L;

	/** How many nested render calls can be in flight before the stack grows. */
	private static final int INITIAL_STACK = 8;
	/** A stack deeper than this means something leaked; it is dropped. */
	private static final int MAX_STACK = 32;

	private static long startedAt;
	private static long durationNanos = DEFAULT_DURATION_NS;
	private static String style = "";
	private static boolean running;

	/**
	 * One entry per open {@code renderItem} call, true when that call pushed a
	 * pose. A plain boolean is not enough: an item render can nest (a bundle,
	 * for example, renders its contents inside its own draw), and a nested call
	 * that did not push must not pop the pose its parent pushed.
	 */
	private static boolean[] pushedByCall = new boolean[INITIAL_STACK];
	private static int depth;

	private KnifeInspect() {
	}

	/** How long one style takes. Heavier knives are given more time. */
	private static long durationFor(String styleId) {
		return switch (styleId) {
			case "butterfly" -> 1_250_000_000L; // a fan needs a beat
			case "karambit" -> 1_150_000_000L;
			case "cleaver" -> 1_350_000_000L;   // heavy, so slow
			case "dagger" -> 1_000_000_000L;    // quick and precise
			case "tanto" -> 1_050_000_000L;     // one clean draw
			case "kukri" -> 1_300_000_000L;     // two turns
			case "machete" -> 1_250_000_000L;   // one heavy chop
			case "stiletto" -> 950_000_000L;    // the fastest of the set
			default -> DEFAULT_DURATION_NS;
		};
	}

	/** Starts (or restarts) the flourish for the given style id. */
	public static void start(String styleId) {
		style = styleId == null ? "" : styleId;
		durationNanos = durationFor(style);
		startedAt = System.nanoTime();
		running = true;
		// A previous frame cannot leave work behind for the next one, but a
		// render that threw mid-draw would. Starting clean is the repair.
		depth = 0;
	}

	public static boolean isRunning() {
		return running;
	}

	/** 0..1 through the flourish; also retires the animation when it ends. */
	public static float progress() {
		if (!running) {
			return 1.0f;
		}
		float t = (System.nanoTime() - startedAt) / (float) durationNanos;
		if (t >= 1.0f) {
			running = false;
			return 1.0f;
		}
		return Math.max(0.0f, t);
	}

	/**
	 * Applies the flourish to the first person pose, if one is running.
	 *
	 * Called from the item renderer for every item it draws, so every guard
	 * here is a real one: only the local player, only the first person
	 * contexts, only a sword while the module is on.
	 *
	 * Every call must be matched by exactly one {@link #end}, including the
	 * calls that do nothing: that is what keeps the bookkeeping balanced.
	 */
	public static void begin(PoseStack pose, LivingEntity entity, ItemStack stack,
			ItemDisplayContext context) {
		boolean pushed = false;
		if (running && appliesTo(entity, stack, context)) {
			float t = progress();
			pose.pushPose();
			frame(pose, style, t, context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND);
			pushed = true;
		}
		push(pushed);
	}

	/** Undoes the {@link #begin} of the same render call, and only that one. */
	public static void end(PoseStack pose) {
		if (depth == 0) {
			return;
		}
		if (pushedByCall[--depth]) {
			pose.popPose();
		}
	}

	private static boolean appliesTo(LivingEntity entity, ItemStack stack, ItemDisplayContext context) {
		if (context != ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
				&& context != ItemDisplayContext.FIRST_PERSON_LEFT_HAND) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.player == null || entity != mc.player) {
			return false;
		}
		CustomKnifeModule module = ModuleManager.get().get(CustomKnifeModule.class);
		return module != null && module.inspectReady(stack);
	}

	private static void push(boolean pushed) {
		if (depth == MAX_STACK) {
			// Something is not balancing its calls. Drop the bookkeeping rather
			// than grow without bound; the renderer will unwind its own poses.
			depth = 0;
		}
		if (depth == pushedByCall.length) {
			boolean[] grown = new boolean[Math.min(MAX_STACK, pushedByCall.length * 2)];
			System.arraycopy(pushedByCall, 0, grown, 0, pushedByCall.length);
			pushedByCall = grown;
		}
		pushedByCall[depth++] = pushed;
	}

	/**
	 * One frame of one style.
	 *
	 * The shared lift and the style motion are both written in terms of
	 * {@code arc}, which is zero at both ends of the animation. The spin angles
	 * use the eased clock and end on whole turns. Together that is what makes
	 * the transform exactly the identity on the first and last frame.
	 */
	private static void frame(PoseStack pose, String style, float raw, boolean left) {
		float t = ease(raw);
		float arc = (float) Math.sin(Math.PI * raw);
		// A slow, small sway that is not symmetric, so a twirl never looks like
		// a metronome.
		float sway = (float) Math.sin(2.0 * Math.PI * raw);
		// The left hand is the mirror image of the right; flipping the Z and Y
		// angles and the sideways translation is the mirror, without scaling
		// the model by -1 (which would flip its faces inside out).
		float m = left ? -1.0f : 1.0f;

		// Lifting the knife out of the hand is shared by every style: it is what
		// makes the flourish read as "looking at the blade" rather than as the
		// hand shaking.
		pose.translate(0.0f, 0.02f * arc, -0.10f * arc);

		switch (style) {
			case "butterfly" -> {
				// A balisong fan: the handle pivots in the palm and the blade
				// sweeps most of a turn around it, with a small wobble so it
				// feels held rather than spun by a machine.
				pose.translate(m * 0.10f * arc, 0.05f * arc, -0.02f * arc);
				pose.mulPose(Axis.YP.rotationDegrees(m * 18.0f * arc));
				pose.mulPose(Axis.XP.rotationDegrees(m * 12.0f * arc));
				pose.mulPose(Axis.ZP.rotationDegrees(m * (360.0f * t + 22.0f * sway)));
			}
			case "karambit" -> {
				// The hook comes up and over: a roll about the blade's own axis
				// combined with a hook turn, so the ring leads and the curve
				// follows it through the air.
				pose.translate(m * 0.06f * arc, 0.09f * arc, -0.07f * arc);
				pose.mulPose(Axis.XP.rotationDegrees(360.0f * t));
				pose.mulPose(Axis.ZP.rotationDegrees(m * -320.0f * arc));
				pose.mulPose(Axis.YP.rotationDegrees(m * 34.0f * arc));
			}
			case "cleaver" -> {
				// Heavy, end over end, with the mass of the blade dragging a
				// little behind the wrist at the start.
				pose.translate(0.0f, 0.12f * arc, -0.14f * arc);
				pose.mulPose(Axis.ZP.rotationDegrees(m * -26.0f * arc));
				pose.mulPose(Axis.YP.rotationDegrees(m * 9.0f * sway));
				pose.mulPose(Axis.XP.rotationDegrees(360.0f * ease(Math.min(1.0f, raw * 1.06f))));
			}
			case "dagger" -> {
				// A finger spin: the dagger rolls about its own axis with the
				// tip staying near the thumb, so the point is what moves.
				pose.translate(m * 0.03f * arc, -0.04f * arc, -0.02f * arc);
				pose.mulPose(Axis.ZP.rotationDegrees(m * 20.0f * arc));
				pose.mulPose(Axis.XP.rotationDegrees(10.0f * arc));
				pose.mulPose(Axis.YP.rotationDegrees(m * 360.0f * t));
			}
			case "tanto" -> {
				// A short draw and slash: pushed forward, swept across the body,
				// then pulled back into the grip.
				pose.translate(0.0f, 0.015f * arc, -0.26f * arc);
				pose.mulPose(Axis.XP.rotationDegrees(-18.0f * arc));
				pose.mulPose(Axis.YP.rotationDegrees(m * (28.0f * arc + 10.0f * sway)));
				pose.mulPose(Axis.ZP.rotationDegrees(m * -70.0f * arc));
			}
			case "kukri" -> {
				// Two turns with the blade riding high, which is how the heavy
				// forward curve of a kukri is actually shown off.
				pose.translate(m * 0.07f * arc, 0.06f * arc, -0.07f * arc);
				pose.mulPose(Axis.XP.rotationDegrees(18.0f * arc));
				pose.mulPose(Axis.YP.rotationDegrees(m * 10.0f * sway));
				pose.mulPose(Axis.ZP.rotationDegrees(m * 720.0f * t));
			}
			case "machete" -> {
				// One heavy chop: the blade goes up and across, then falls
				// through the swing, and comes back to rest.
				pose.translate(0.0f, 0.10f * arc, -0.16f * arc);
				pose.mulPose(Axis.XP.rotationDegrees(14.0f * arc));
				pose.mulPose(Axis.YP.rotationDegrees(m * 38.0f * arc));
				pose.mulPose(Axis.ZP.rotationDegrees(m * -150.0f * arc));
			}
			case "stiletto" -> {
				// Fastest of the set: a straight thrust and a snap roll, which is
				// what a needle blade is for.
				pose.translate(0.0f, 0.02f * arc, -0.34f * arc);
				pose.mulPose(Axis.ZP.rotationDegrees(m * 14.0f * arc));
				pose.mulPose(Axis.XP.rotationDegrees(88.0f * arc));
				pose.mulPose(Axis.YP.rotationDegrees(m * 720.0f * t));
			}
			default -> pose.mulPose(Axis.ZP.rotationDegrees(m * 360.0f * t));
		}
	}

	/** Smoothstep: no snap at the start, no stop at the end. */
	private static float ease(float t) {
		float clamped = Math.max(0.0f, Math.min(1.0f, t));
		return clamped * clamped * (3.0f - 2.0f * clamped);
	}
}
