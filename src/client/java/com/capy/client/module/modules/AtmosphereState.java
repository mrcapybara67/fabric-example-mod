package com.capy.client.module.modules;

/**
 * The live atmosphere values, shared between the module and the render hooks.
 *
 * <h2>Why a store instead of a direct call</h2>
 * The hooks that tint the sky, thicken the fog and dim the lightmap run inside
 * vanilla's renderer, on the render thread, at points where calling a module
 * would mean walking the module list — and, worse, allocating — once per frame
 * per hook. This store is a plain block of floats: the module writes it on the
 * client tick, the hooks read it, and neither side has to know about the other.
 *
 * <h2>Why the fields are volatile</h2>
 * A client tick and a frame are not interleaved on one thread: the tick runs on
 * the client thread and the renderer reads these values on the render thread.
 * A float read and write are atomic on the JVM, so a frame always sees either
 * the previous value or the next one, never a torn mix of the two. The
 * <em>pairs</em> (a tint and its strength) can be one tick apart for a frame,
 * which is invisible: both are interpolated at four e-folds a second.
 *
 * <h2>Why the values are eased and not snapped</h2>
 * Every target is lerped towards on the tick. A slider dragged from 0 to 100
 * would otherwise be a single frame of whiplash — the sky, the fog and the haze
 * all jumping at once — and switching a preset would read as a hard cut. The
 * easing makes a preset change a swell instead.
 */
public final class AtmosphereState {
	/** Enabled flag — every hook checks this first and returns untouched if false. */
	public static volatile boolean active = false;

	// Sky tint: blend strength (0 = vanilla, 1 = full tint) and the tint itself.
	public static volatile float skyHot = 0f;
	public static volatile float skyHotTarget = 0f;
	public static volatile float skyR = 0.55f;
	public static volatile float skyG = 0.72f;
	public static volatile float skyB = 1.00f;

	/** Fog distance multiplier: 1 = vanilla, above 1 compresses the distances. */
	public static volatile float fogHot = 1f;
	public static volatile float fogHotTarget = 1f;

	/** Ambient warmth: lifts the floor of the lightmap. */
	public static volatile float fbAmb = 0f;
	public static volatile float fbAmbTarget = 0f;

	/** Screen haze strength, and the colour it is drawn in. */
	public static volatile float fbHot = 0f;
	public static volatile float fbHotTarget = 0f;
	public static volatile float hazeR = 1.00f;
	public static volatile float hazeG = 0.85f;
	public static volatile float hazeB = 0.60f;

	/**
	 * Time of day override, in the friendly convention: 0 = midnight,
	 * 0.25 = sunrise, 0.5 = noon, 0.75 = sunset. Negative means "use the
	 * server's time".
	 *
	 * <p>Vanilla counts from sunrise, so the hooks convert this before writing
	 * it into {@code getDayTime}. Keeping the friendly convention here is what
	 * lets the module's labels ("sunrise", "noon") mean what they say.</p>
	 */
	public static volatile float timeDay = -1f;
	public static volatile float timeDayTarget = -1f;

	/** Weather overrides; negative means "use the server's weather". */
	public static volatile float rainOvr = -1f;
	public static volatile float rainOvrTarget = -1f;
	public static volatile float thunderOvr = -1f;
	public static volatile float thunderOvrTarget = -1f;

	/** Lightmap brightness multiplier: 1 = unchanged, 0.5 = dimmer, 2 = brighter. */
	public static volatile float fbLive = 1f;
	public static volatile float fbLiveTarget = 1f;

	/** E-folds per second for every eased value. */
	private static final float LERP_SPEED = 4.0f;

	private AtmosphereState() {
	}

	/** Eases every live value towards its target. Called once per client tick. */
	public static void tick(float dt) {
		float step = Math.max(0f, Math.min(1f, LERP_SPEED * dt));
		skyHot = ease(skyHot, skyHotTarget, step);
		fogHot = ease(fogHot, fogHotTarget, step);
		fbAmb = ease(fbAmb, fbAmbTarget, step);
		fbHot = ease(fbHot, fbHotTarget, step);
		fbLive = ease(fbLive, fbLiveTarget, step);
		timeDay = easeOverride(timeDay, timeDayTarget, step);
		rainOvr = easeOverride(rainOvr, rainOvrTarget, step);
		thunderOvr = easeOverride(thunderOvr, thunderOvrTarget, step);
	}

	/** Puts every target back to vanilla and drops the live values with them. */
	public static void neutral() {
		skyHotTarget = 0f;
		fogHotTarget = 1f;
		fbHotTarget = 0f;
		fbAmbTarget = 0f;
		fbLiveTarget = 1f;
		timeDayTarget = -1f;
		rainOvrTarget = -1f;
		thunderOvrTarget = -1f;
	}

	private static float ease(float current, float target, float step) {
		return current + (target - current) * step;
	}

	/**
	 * Easing for the two optional values.
	 *
	 * <p>A target below zero is "off", and is reached by snapping rather than by
	 * sliding through zero: a time override eased towards -1 would spend a tick
	 * or two at a negative fraction, which the hook reads as a legal time and
	 * would put the sun in a position nobody asked for. Enabling snaps the
	 * other way, so the first frame of an override is already at the requested
	 * time instead of rising out of midnight.</p>
	 */
	private static float easeOverride(float current, float target, float step) {
		if (target < 0f) {
			return -1f;
		}
		if (current < 0f) {
			return target;
		}
		return ease(current, target, step);
	}
}
