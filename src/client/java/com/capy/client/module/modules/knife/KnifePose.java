package com.capy.client.module.modules.knife;

import com.capy.client.module.ModuleManager;
import com.capy.client.module.modules.CustomKnifeModule;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Everything the first person view does with a custom knife: how it sits in the
 * hand, how it swings, and the inspect flourish.
 *
 * Where the transform lives, and why: {@code ItemInHandRenderer.renderItem} is
 * called after the arm has already been drawn and swung, and before the item's
 * own display transform is applied. A pose pushed at that point is therefore in
 * <em>hand space</em> - the space the arm model and the vanilla swing work in -
 * so translating here moves the knife relative to the fist, and rotating here
 * turns it about the fist. That is exactly the space a "this is how you hold
 * this knife" rule needs, and it is why the placement is not a per-model
 * display transform: those numbers are expressed around the item's own origin,
 * which is the corner of its 16 unit box, and are impossible to reason about.
 *
 * Three independent layers, applied in this order:
 *
 *   1. <b>stance</b> - the resting placement of the style. Not identity: a
 *      karambit is held in a reverse grip, so it is turned a half turn about
 *      the fist and hangs below it, while a stiletto sits a little high and
 *      points forward. This is the layer that makes each knife feel held
 *      rather than merely rendered.
 *   2. <b>swing</b> - the attack animation, driven by vanilla's own
 *      {@code attackAnim} so it starts and ends exactly when the arm does.
 *      Every style has its own motion: a balisong fans, a cleaver is dropped
 *      through the cut, a stiletto snaps straight forward.
 *   3. <b>inspect</b> - the on demand flourish, unchanged in intent.
 *
 * The swing and the flourish are both multiplied by an envelope that is zero on
 * the first and last frame, and the spins end on whole turns, so neither can
 * ever leave the knife crooked in the hand. The stance is deliberately not
 * enveloped - it is the resting pose, and it is the identity for the styles
 * that are held normally, so it cannot drift either.
 */
public final class KnifePose {
	/** Default length of a flourish, in nanoseconds. */
	private static final long DEFAULT_DURATION_NS = 1_100_000_000L;

	/** How many nested render calls can be in flight before the stack grows. */
	private static final int INITIAL_STACK = 8;
	/** A stack deeper than this means something leaked; it is dropped. */
	private static final int MAX_STACK = 32;

	/** The attack progress vanilla computed for this frame, 0..1. */
	private static float attack;

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

	private KnifePose() {
	}

	// ------------------------------------------------------------- per frame

	/**
	 * Called once per frame, before the hands are drawn, with the same partial
	 * tick vanilla itself used for the arm swing.
	 *
	 * Sampling the attack animation once per frame instead of once per hand
	 * keeps both hands and the arm on exactly the same clock, and it is the
	 * only value that makes the swing smooth: reading it per draw call would
	 * sample the interpolated animation mid-frame.
	 */
	public static void frame(LocalPlayer player, float partialTick) {
		attack = player == null ? 0f : clamp01(player.getAttackAnim(partialTick));
		if (running && progress() >= 1f) {
			running = false;
		}
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

	/** 0..1 through the flourish; also retires the animation when it ends. */
	public static float progress() {
		if (!running) {
			return 1.0f;
		}
		float t = (System.nanoTime() - startedAt) / (float) durationNanos;
		if (t >= 1.0f) {
			return 1.0f;
		}
		return Math.max(0.0f, t);
	}

	// -------------------------------------------------------------- transform

	/**
	 * Applies the knife's pose to the first person hand, if one is running.
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
		CustomKnifeModule module = module();
		if (module != null && appliesTo(module, entity, stack, context)) {
			pose.pushPose();
			String styleId = module.styleId();
			boolean left = context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
			stance(pose, styleId, left);
			if (attack > 0.001f && attack < 0.999f) {
				swing(pose, styleId, attack, left);
			}
			if (running) {
				float t = progress();
				if (t < 1f) {
					// The flourish keeps the style it was started with, so changing
					// the setting mid animation cannot swap the motion under it.
					flourish(pose, style, t, left);
				}
			}
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

	private static CustomKnifeModule module() {
		return ModuleManager.get().get(CustomKnifeModule.class);
	}

	private static boolean appliesTo(CustomKnifeModule module, LivingEntity entity, ItemStack stack,
			ItemDisplayContext context) {
		if (context != ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
				&& context != ItemDisplayContext.FIRST_PERSON_LEFT_HAND) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.player == null || entity != mc.player) {
			return false;
		}
		// armed(), not inspectReady(): the flourish setting must not switch the
		// resting pose and the swing off with it.
		return module.armed(stack);
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

	// ---------------------------------------------------------------- stances

	/**
	 * How each style rests in the hand.
	 *
	 * The units are metres of hand space and degrees about the fist, so these
	 * are small numbers by design: a knife that is a hand's width from the fist
	 * is not being held, it is floating. The karambit is the one real change of
	 * grip: a karambit is held with the ring on the index finger and the blade
	 * running back along the forearm, i.e. a half turn from a sword. Every
	 * other style is a small tilt that follows the shape of the blade - a
	 * cleaver hangs a little, a stiletto rides a little higher.
	 */
	private static void stance(PoseStack pose, String style, boolean left) {
		float m = left ? -1.0f : 1.0f;
		switch (style) {
			case "karambit" -> {
				// Reverse grip: the ring stays at the fist and the hook hangs
				// below it, pointing back the way a karambit is actually used.
				pose.translate(0.0f, 0.015f, 0.01f);
				pose.mulPose(Axis.ZP.rotationDegrees(180.0f));
			}
			case "cleaver" -> {
				pose.translate(0.0f, -0.030f, 0.0f);
				pose.mulPose(Axis.ZP.rotationDegrees(m * -8.0f));
			}
			case "machete" -> {
				pose.translate(0.0f, -0.025f, 0.0f);
				pose.mulPose(Axis.ZP.rotationDegrees(m * -6.0f));
			}
			case "kukri" -> {
				pose.translate(0.0f, -0.018f, -0.008f);
				pose.mulPose(Axis.ZP.rotationDegrees(m * -10.0f));
			}
			case "tanto" -> {
				pose.translate(0.0f, -0.004f, 0.0f);
				pose.mulPose(Axis.ZP.rotationDegrees(m * -12.0f));
			}
			case "dagger" -> {
				pose.translate(0.0f, 0.010f, -0.010f);
				pose.mulPose(Axis.ZP.rotationDegrees(m * 5.0f));
			}
			case "stiletto" -> {
				pose.translate(0.0f, 0.014f, -0.020f);
				pose.mulPose(Axis.ZP.rotationDegrees(m * 7.0f));
			}
			case "butterfly" -> {
				pose.translate(0.0f, 0.004f, -0.010f);
				pose.mulPose(Axis.ZP.rotationDegrees(m * 3.0f));
			}
			default -> {
			}
		}
	}

	// ----------------------------------------------------------------- swings

	/**
	 * The attack motion of each style, as an additional transform on top of the
	 * vanilla arm swing.
	 *
	 * Vanilla already swings the arm for every item; what it cannot do is give
	 * a cleaver the weight of a cleaver. These motions are therefore written in
	 * the same space and the same clock as the vanilla swing, so the two add up
	 * instead of fighting: the arm leads, the knife follows with its own
	 * character.
	 *
	 * The envelope {@code arc} is zero at both ends of the animation, which is
	 * what guarantees that the last frame is exactly the resting pose - a knife
	 * left a few degrees off after a hit looks broken, not animated.
	 */
	private static void swing(PoseStack pose, String style, float t, boolean left) {
		float m = left ? -1.0f : 1.0f;
		float arc = arc(t);
		// A back and forth component, so a sweep reads as a slash rather than
		// as a rotation that starts and stops.
		float back = (float) Math.sin(2.0 * Math.PI * t) * arc;
		float slam = arc * arc;

		switch (style) {
			case "butterfly" -> {
				// The fan: the handle pivots in the palm and the blade whips
				// around it, twice, because a balisong is never still.
				pose.translate(m * 0.014f * arc, 0.012f * arc, 0.0f);
				pose.mulPose(Axis.YP.rotationDegrees(m * 26.0f * back));
				pose.mulPose(Axis.ZP.rotationDegrees(m * 46.0f * back));
				pose.mulPose(Axis.XP.rotationDegrees(14.0f * arc));
			}
			case "karambit" -> {
				// The hook: pulled in towards the body and rolled over, which is
				// how the curved blade is driven through a target.
				pose.translate(m * -0.020f * arc, 0.010f * arc, 0.026f * arc);
				pose.mulPose(Axis.ZP.rotationDegrees(m * -46.0f * arc));
				pose.mulPose(Axis.YP.rotationDegrees(m * 30.0f * back));
				pose.mulPose(Axis.XP.rotationDegrees(-12.0f * arc));
			}
			case "cleaver" -> {
				// Weight: the blade is hauled up, then dropped through the cut.
				pose.translate(0.0f, 0.055f * arc - 0.095f * slam, 0.018f * arc);
				pose.mulPose(Axis.ZP.rotationDegrees(m * 30.0f * arc - m * 18.0f * slam));
				pose.mulPose(Axis.XP.rotationDegrees(10.0f * arc));
			}
			case "dagger" -> {
				// A short, fast thrust with the point doing the work.
				pose.translate(m * 0.008f * arc, 0.0f, -0.170f * slam - 0.020f * arc);
				pose.mulPose(Axis.ZP.rotationDegrees(m * -9.0f * arc));
				pose.mulPose(Axis.XP.rotationDegrees(12.0f * arc));
			}
			case "tanto" -> {
				// A draw and a cut: the blade is pulled back along the body,
				// then swept across.
				pose.translate(m * 0.026f * back, 0.0f, 0.055f * arc - 0.030f * slam);
				pose.mulPose(Axis.YP.rotationDegrees(m * 34.0f * back));
				pose.mulPose(Axis.ZP.rotationDegrees(m * -20.0f * arc));
			}
			case "kukri" -> {
				// The wide, forward-weighted arc the heavy belly is made for.
				pose.translate(m * 0.030f * arc, 0.020f * arc, -0.010f * arc);
				pose.mulPose(Axis.YP.rotationDegrees(m * 44.0f * arc));
				pose.mulPose(Axis.ZP.rotationDegrees(m * -16.0f * arc));
				pose.mulPose(Axis.XP.rotationDegrees(8.0f * arc));
			}
			case "machete" -> {
				// Overhead chop: up, then down hard, with the wrist rolling.
				pose.translate(0.0f, 0.075f * arc - 0.085f * slam, 0.010f * arc);
				pose.mulPose(Axis.ZP.rotationDegrees(m * 38.0f * arc - m * 12.0f * slam));
				pose.mulPose(Axis.XP.rotationDegrees(12.0f * arc));
			}
			case "stiletto" -> {
				// The fastest of the set: a needle straight forward.
				pose.translate(m * 0.006f * arc, 0.006f * arc, -0.210f * slam);
				pose.mulPose(Axis.ZP.rotationDegrees(m * -7.0f * arc));
				pose.mulPose(Axis.XP.rotationDegrees(6.0f * arc));
			}
			default -> pose.mulPose(Axis.ZP.rotationDegrees(m * 18.0f * back));
		}
	}

	// -------------------------------------------------------------- flourish

	/**
	 * One frame of the inspect flourish.
	 *
	 * The shared lift and the style motion are both written in terms of
	 * {@code arc}, which is zero at both ends of the animation. The spin angles
	 * use the eased clock and end on whole turns. Together that is what makes
	 * the transform exactly the identity on the first and last frame.
	 */
	private static void flourish(PoseStack pose, String style, float raw, boolean left) {
		float t = smooth(raw);
		float arc = arc(raw);
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
				pose.mulPose(Axis.XP.rotationDegrees(360.0f * smooth(Math.min(1.0f, raw * 1.06f))));
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

	/** Zero at both ends of the interval, one in the middle. */
	private static float arc(float t) {
		return (float) Math.sin(Math.PI * clamp01(t));
	}

	/** Smoothstep: no snap at the start, no stop at the end. */
	private static float smooth(float t) {
		float clamped = clamp01(t);
		return clamped * clamped * (3.0f - 2.0f * clamped);
	}

	private static float clamp01(float value) {
		return value < 0.0f ? 0.0f : (value > 1.0f ? 1.0f : value);
	}
}
