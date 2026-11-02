package com.capy.client.module.modules;

import com.capy.client.config.ConfigValues;
import com.capy.client.mixin.FogRendererAccessor;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.Category;
import com.capy.client.module.ChoiceSetting;
import com.capy.client.module.Configurable;
import com.capy.client.module.Module;
import com.capy.client.module.SliderSetting;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Random;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

/**
 * Custom Sky — replaces the sky with one of ten animated themes.
 *
 * <h2>Where it draws</h2>
 * The themes are drawn as one flat painting hung in front of the camera. Each
 * theme works in <em>framebuffer pixels</em> — the window's real resolution, not
 * the rounded GUI scale — so a line is one screen pixel wide at 1080p and the
 * artwork is never assembled on a coarse grid and stretched. {@link Canvas}
 * maps those pixels onto a plane at the far end of the view frustum, scaled by
 * the field of view the frame was actually projected with.
 *
 * <h2>Why the pose is the inverse of the model view</h2>
 * In 1.21.11 the world is drawn from a frame graph and the camera rotation is not
 * baked into a pose stack: {@code LevelRenderer} multiplies the view matrix into
 * {@code RenderSystem.getModelViewStack()} before the graph runs, and everything
 * inside the pass — entities included — is submitted in camera space with an
 * identity pose. The vertex shader applies that model view to whatever we hand
 * it, so a painting submitted with an identity pose would be fixed in the
 * <em>world</em>: it would face one compass direction, slide out of view when the
 * player turns, and be edge-on when looked at sideways.
 *
 * Pre-multiplying the pose by the inverse of the model view cancels the shader's
 * rotation exactly, which puts the painting back in camera space: it follows the
 * view, and its depth stays measured along the view axis.
 *
 * <h2>Depth</h2>
 * The plane carries the projection's far depth with the depth test on and depth
 * writing off, so the painting lands only on pixels the world did not cover.
 * Terrain, entities and clouds hide it exactly as they hide the vanilla sky, and
 * a theme is never drawn over a mountain. Every layer is a quad at the same
 * depth, the test passes on equality and the blend is translucent order, so the
 * layers stack like transparencies — gradients, glows and the eclipse's veil all
 * depend on that order rather than on depth.
 *
 * The hook is {@code AFTER_ENTITIES}, because geometry submitted before the frame
 * graph runs has no pass to land in, while drawing after the entities is inside
 * the pass.
 *
 * <h2>Animation</h2>
 * The clock is wall-clock seconds scaled by the speed slider, and {@code spin}
 * is a second clock in radians. Every theme animates from those two numbers and
 * the deterministic noise below, so at speed 0 every sky holds still and every
 * coefficient in the themes reads as "per second".
 */
public class CustomSkyModule extends Module implements Configurable {
	private static final int VANILLA = 0;
	private static final int BLACK_HOLE = 1;
	private static final int SPACE = 2;
	private static final int AURORA = 3;
	private static final int SUNSET = 4;
	private static final int DEEP_OCEAN = 5;
	private static final int CHERRY = 6;
	private static final int VAPORWAVE = 7;
	private static final int VOID = 8;
	private static final int ECLIPSE = 9;

	private static final float TWO_PI = (float) (Math.PI * 2.0);
	/** The clock is wrapped at this many seconds to keep float precision exact. */
	private static final float WRAP_SECONDS = 900f;
	private static final int BUFFER_BYTES = 1024 * 1024;
	/**
	 * The painting is a little larger than the viewport.
	 *
	 * The plane is sized from the field of view the frame was projected with, and
	 * a pixel of rounding between the two would otherwise leave a thin unpainted
	 * seam along an edge.
	 */
	private static final double OVERSCAN = 1.02;
	/**
	 * The phase the painting is drawn in, before every other overlay.
	 *
	 * The ESP and the bracket overlays draw without writing depth, so whichever
	 * of them ran last would be erased by an opaque sky. Ordering the sky into
	 * its own earlier phase makes the result independent of the order in which
	 * the modules happen to be switched on.
	 */
	private static final Identifier SKY_PHASE = Identifier.fromNamespaceAndPath("capy", "sky");

	private final ChoiceSetting theme = setting(new ChoiceSetting("theme", VANILLA,
			"vanilla", "black_hole", "space", "aurora", "sunset_glow",
			"deep_ocean", "cherry_blossom", "vaporwave", "void", "eclipse"));
	private final SliderSetting speed = setting(new SliderSetting("speed", 10, 0, 30, 1));
	private final SliderSetting opacity = setting(new SliderSetting("opacity", 100, 0, 100, 2));
	private final BooleanSetting stars = setting(new BooleanSetting("stars", true));
	private final BooleanSetting fog = setting(new BooleanSetting("fog", false));

	private final Random random = new Random();
	private boolean listening;
	/** Animation clock in seconds, already multiplied by the speed slider. */
	private float time;
	/** Rotation of the turning themes, in radians, already scaled by speed. */
	private float spin;
	/** The eased speed, so moving the slider is a throttle rather than a step. */
	private float easedSpeed = -1f;
	/** The viewport of the last frame, which the shared star drift is scaled by. */
	private float frameW = 1920f;
	private float frameH = 1080f;
	/** Where the view is pointing, in degrees, this frame. */
	private float viewYaw;
	private float viewPitch;
	/**
	 * How far a layer drawn in screen space has to be moved down the frame
	 * because the player looked up, in pixels.
	 *
	 * <p>A sky is not a wallpaper: looking up moves the terrain down the screen,
	 * and anything painted on the sky has to move with it. Stars, dust and the
	 * aurora have no bearing of their own to project - they are a field - so the
	 * whole field is slid by this much instead. Without it they kept their
	 * screen height at every pitch, which is what makes a theme look pinned to
	 * the crosshair.</p>
	 */
	private float skyPitch;
	/**
	 * The heading the current theme was switched on at.
	 *
	 * Sky bodies are placed relative to this, and it is a fixed bearing of the
	 * world, so a theme's subject stays in the sky as the player turns instead
	 * of following the crosshair. See {@link #anchorOf(int)}.
	 */
	private float skyOriginYaw;
	/** The theme the last frame drew, so a change can re-anchor. */
	private int lastTheme = -1;
	/**
	 * The camera's basis for this frame: what "right", "up" and "forward"
	 * mean on screen. A sky body is placed by projecting its world direction
	 * through these, which is the only way it can slide across the frame at the
	 * same rate the world does.
	 */
	private float rightX = 1f;
	private float rightY;
	private float rightZ;
	private float upX;
	private float upY = 1f;
	private float upZ;
	private float fwdX;
	private float fwdY;
	private float fwdZ = -1f;
	/** Tangents of half the vertical and horizontal field of view. */
	private float tanHalfV = 0.46f;
	private float tanHalfH = 0.72f;
	/** Reused result of {@link #skyPoint(float, float)}; never keep the array. */
	private final float[] skyPoint = new float[2];
	private long lastNanos;
	/** Clock value at which the next shooting star is allowed to appear. */
	private float nextShootingStar = 4f;
	private float shootingStarAt = -1f;
	private float shootingStarX;
	private float shootingStarY;
	private float shootingStarAngle;
	/** Fog as it was before the module switched it off. */
	private boolean fogWasEnabled = true;
	/** True while the module is the one holding fog down. */
	private boolean fogHeld;

	private MultiBufferSource.BufferSource batch;
	private ByteBufferBuilder buffer;

	public CustomSkyModule() {
		super(Category.VISUAL, "custom_sky");
		WorldRenderEvents.AFTER_ENTITIES.addPhaseOrdering(SKY_PHASE, Event.DEFAULT_PHASE);
	}

	@Override
	protected void onToggle(boolean state) {
		if (state) {
			if (!listening) {
				WorldRenderEvents.AFTER_ENTITIES.register(SKY_PHASE, this::render);
				listening = true;
			}
			lastNanos = 0L;
			// Re-anchor on the next frame the theme draws.
			lastTheme = -1;
			shootingStarAt = -1f;
			// Not on the very first frame after switching on: a star streaking
			// across an empty sky the instant the theme appears reads as a glitch,
			// and the first one is the one the player judges it by.
			nextShootingStar = time + 4f + random.nextFloat() * 6f;
			return;
		}
		releaseFog();
		lastNanos = 0L;
		dropBatch();
	}

	// ---------------------------------------------------------------- render

	private void render(WorldRenderContext context) {
		if (!isEnabled()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return;
		}
		// The toggle drops the source while this listener is still registered
		// for one more frame; a batch whose builder was closed writes into freed
		// memory, so the pair is reset instead of reused.
		if (batch != null && buffer == null) {
			dropBatch();
		}
		int index = theme.index();
		if (index == VANILLA) {
			// Nothing is painted, so nothing may be held down either.
			releaseFog();
			return;
		}
		long now = System.nanoTime();
		float dt = lastNanos == 0L ? 0f : (now - lastNanos) / 1.0e9f;
		lastNanos = now;
		dt = Math.max(0f, Math.min(0.1f, dt));
		// The slider is eased rather than applied raw. A raw value jumps every
		// motion in the theme by a whole step between two frames, which reads as
		// a stutter; a single lerp turns the slider into a throttle and makes a
		// speed change a swell instead of a jolt.
		float target = (float) speed.real();
		if (easedSpeed < 0f) {
			easedSpeed = target;
		}
		easedSpeed += (target - easedSpeed) * Math.min(1f, dt * 2.2f);
		float speedValue = easedSpeed;
		time += dt * speedValue;
		spin += dt * speedValue;
		wrapClock();

		// Native resolution: the painting is built in the pixels the GPU will
		// receive, so nothing is drawn on the GUI grid and blown up.
		float w = mc.getWindow().getWidth();
		float h = mc.getWindow().getHeight();
		if (w < 8f || h < 8f) {
			return;
		}
		frameW = w;
		frameH = h;
		float alpha = (float) opacity.real();
		if (alpha <= 0f) {
			// A theme at zero opacity paints nothing, so it must not keep the fog
			// switched off either: the module would be changing the world with no
			// visible reason to.
			releaseFog();
			return;
		}
		// Held only once this frame is known to paint something.
		applyFog();

		// Near the far plane of the projection: far enough that no terrain can be
		// in front of it, close enough that the depth value still has room below
		// the cleared 1.0.
		float far = mc.gameRenderer.getDepthFar();
		if (!(far > 8f) || !Float.isFinite(far)) {
			far = 512f;
		}
		float depth = Math.max(16f, far * 0.9f);

		float fov = CustomFOVModule.lastFov();
		if (!Float.isFinite(fov) || fov < 10f || fov > 170f) {
			fov = 70f;
		}
		fov = Math.max(30f, Math.min(150f, fov));
		double halfH = depth * Math.tan(Math.toRadians(fov * 0.5)) * OVERSCAN;
		double aspect = w / (double) Math.max(1f, h);
		double halfW = halfH * aspect;

		// Where the sky is pointing. Every sky body is placed by compass azimuth
		// and elevation, so turning the view slides it across the sky instead of
		// dragging it along with the crosshair: look left, and the black hole
		// leaves the screen the way the sun does.
		// The camera, not the body. A sky is oriented by the direction the frame
		// was drawn looking, and only the camera knows that: the player's yaw
		// is the body's heading, which lags behind the mouse while turning and
		// does not move at all in third person or with a smoothing module on.
		// Reading it was why a theme stayed in the middle of the screen while
		// the player looked around - the anchor had one fixed bearing to slide
		// against, and the value it was sliding against never changed.
		org.joml.Vector3fc forward = mc.gameRenderer.getMainCamera().forwardVector();
		viewYaw = (float) Math.toDegrees(Math.atan2(-forward.x(), forward.z()));
		viewPitch = (float) Math.toDegrees(-Math.asin(Math.max(-1.0, Math.min(1.0, forward.y()))));			// A theme's subject hangs at a fixed bearing of the world, never at
			// the heading the module happened to be switched on at. Anchoring on
			// the current view is what made a theme look pinned to the crosshair:
			// re-enabling it, or changing theme, put the subject back in the
			// middle of the screen, so it never behaved like part of the sky.
			// With a fixed bearing the sky stays where it is and the player turns
			// to find it, exactly as they would the sun.
			skyOriginYaw = anchorOf(index);
			lastTheme = index;
		// The camera's own basis. A bearing is not a screen offset: the same
		// azimuth lands in a different place at every elevation, and the whole
		// picture has to move with the camera exactly as the world does. The
		// old code shifted a body horizontally by the yaw difference, which is
		// a flat approximation - it left the subject glued to the crosshair at
		// any pitch other than zero, and left the rest of the sky black because
		// nothing else was projected at all.
		org.joml.Quaternionf rotation = mc.gameRenderer.getMainCamera().rotation();
		org.joml.Vector3f axis = new org.joml.Vector3f(1f, 0f, 0f);
		rotation.transform(axis);
		rightX = axis.x;
		rightY = axis.y;
		rightZ = axis.z;
		rotation.transform(axis.set(0f, 1f, 0f));
		upX = axis.x;
		upY = axis.y;
		upZ = axis.z;
		rotation.transform(axis.set(0f, 0f, -1f));
		fwdX = axis.x;
		fwdY = axis.y;
		fwdZ = axis.z;
		tanHalfV = (float) Math.tan(Math.toRadians(fov * 0.5));
		tanHalfH = tanHalfV * (float) aspect;
		// Pitch to pixels, through the same tangent the frame is projected with.
		// The camera reports a negative pitch when it looks up, and looking up
		// has to send the sky down, so the sign is reversed here.
		skyPitch = -viewPitch * (frameH * 0.5f) / Math.max(0.02f, tanHalfV) * 0.0174533f;

		try {
			// A fresh stack, deliberately not the world's.
			//
			// The pose stack handed to this event already carries whatever the
			// pass and everything submitted before us put on it. Inheriting it
			// applied the view twice, and the painting came out rotated by the
			// pass and pinned to one heading instead of filling the viewport - on
			// screen that is "the theme sits on the crosshair and the rest of the
			// sky is black". Starting from identity and cancelling only the model
			// view puts the vertices in camera space, which is what a sky wants.
			Matrix4f inverse = new Matrix4f(RenderSystem.getModelViewMatrix()).invert();
			if (!finite(inverse)) {
				// A singular view has no inverse, and JOML answers with a matrix of
				// NaN - which blanks the whole sky. The camera's own rotation is
				// what the view is in the normal frame, so it is used instead.
				inverse = new Matrix4f().rotation(new org.joml.Quaternionf(rotation).conjugate());
			}
			PoseStack stack = new PoseStack();
			stack.last().pose().mul(inverse);

			VertexConsumer vc = batch().getBuffer(RenderTypes.debugQuads());
			Canvas canvas = new Canvas(vc, stack.last(), w, h, halfW, halfH, depth);
			draw(canvas, index, w, h, alpha);
			batch().endBatch();
		} catch (RuntimeException e) {
			// A broken theme must cost the sky, never the frame.
			dropBatch();
		}
	}

	/**
	 * The compass bearing each theme's subject hangs at, in degrees.
	 *
	 * <p>They are spread around the world so no two themes put their subject in
	 * the same place: a black hole to the south-south-east, the eclipse to the
	 * north, the sunset due west, the morning sun of the blossom theme to the
	 * east. That spacing is also what stops a theme change from looking like the
	 * same picture with different colours.
	 */
	private static float anchorOf(int index) {
		return switch (index) {
			case BLACK_HOLE -> 24f;
			case SPACE -> 0f;
			case AURORA -> 118f;
			case SUNSET -> 84f;
			case DEEP_OCEAN -> -40f;
			case CHERRY -> -130f;
			case VAPORWAVE -> 62f;
			case VOID -> 156f;
			case ECLIPSE -> 180f;
			default -> 0f;
		};
	}

	/** Screen X of a point on the sky, given its compass azimuth in degrees. */
	private float skyX(float azimuth) {
		return skyPoint(azimuth, 0f)[0];
	}

	/**
	 * Where a compass bearing and an elevation land on screen, projected through
	 * the camera's own basis.
	 *
	 * <p>The body's direction is built in world space (yaw 0 is south, +Y is up),
	 * resolved against the camera's right/up/forward vectors, and divided by its
	 * depth - a real perspective projection, with the frame's own field of view.
	 * That is what makes a theme behave like the sun: turning left slides it
	 * right at the rate the terrain moves, looking up slides it down, and looking
	 * away from it takes it off the frame instead of parking it on the
	 * crosshair.</p>
	 *
	 * <p>The caller must copy the two values immediately; the array is reused so
	 * a frame full of stars allocates nothing.</p>
	 */
	private float[] skyPoint(float azimuth, float elevation) {
		double az = Math.toRadians(azimuth);
		double el = Math.toRadians(elevation);
		double cosEl = Math.cos(el);
		float dx = (float) (-Math.sin(az) * cosEl);
		float dy = (float) Math.sin(el);
		float dz = (float) (Math.cos(az) * cosEl);
		float depth = dx * fwdX + dy * fwdY + dz * fwdZ;
		float sx = dx * rightX + dy * rightY + dz * rightZ;
		float sy = dx * upX + dy * upY + dz * upZ;
		if (depth <= 0.10f) {
			// Behind the eye there is no projection at all. The body is put well
			// outside the frame on the side it left by, so it leaves the screen
			// instead of streaking across the middle as its depth crosses zero.
			skyPoint[0] = frameW * 0.5f + (sx >= 0f ? 1f : -1f) * frameW * 2.5f;
			skyPoint[1] = clampSkyY(frameH * 0.5f - (sy / 0.10f) / tanHalfV * frameH * 0.5f);
			return skyPoint;
		}
		skyPoint[0] = frameW * 0.5f + (sx / depth) / tanHalfH * frameW * 0.5f;
		skyPoint[1] = clampSkyY(frameH * 0.5f - (sy / depth) / tanHalfV * frameH * 0.5f);
		return skyPoint;
	}

	/** True when every element of a matrix is a real number. */
	private static boolean finite(Matrix4f matrix) {
		return Float.isFinite(matrix.m00()) && Float.isFinite(matrix.m01())
				&& Float.isFinite(matrix.m02()) && Float.isFinite(matrix.m03())
				&& Float.isFinite(matrix.m10()) && Float.isFinite(matrix.m11())
				&& Float.isFinite(matrix.m12()) && Float.isFinite(matrix.m13())
				&& Float.isFinite(matrix.m20()) && Float.isFinite(matrix.m21())
				&& Float.isFinite(matrix.m22()) && Float.isFinite(matrix.m23())
				&& Float.isFinite(matrix.m30()) && Float.isFinite(matrix.m31())
				&& Float.isFinite(matrix.m32()) && Float.isFinite(matrix.m33());
	}

	/** Keeps a projected body near enough to be drawn, never at infinity. */
	private float clampSkyY(float y) {
		float limit = frameH * 4f;
		return Math.max(-limit, Math.min(limit, y));
	}

	/** Degrees folded into -180..180, so a sky body has one position per turn. */
	private static float wrapDegrees(float degrees) {
		float wrapped = degrees % 360f;
		if (wrapped < -180f) {
			wrapped += 360f;
		} else if (wrapped > 180f) {
			wrapped -= 360f;
		}
		return wrapped;
	}

	/** Keeps both clocks inside the range a float resolves exactly. */
	private void wrapClock() {
		if (time < WRAP_SECONDS) {
			return;
		}
		time -= WRAP_SECONDS;
		spin %= TWO_PI;
		// The star timers are absolute clock values, so they cannot simply be
		// shifted past the wrap: the streak is dropped and the next one is put
		// a few seconds out.
		shootingStarAt = -1f;
		nextShootingStar = Math.max(2f, nextShootingStar - WRAP_SECONDS);
	}

	/**
	 * Holds distance fog down while the setting asks for it.
	 *
	 * The state is only written while the module owns it, and the state found on
	 * the first frame is the one put back: pressing the fog key while a theme is
	 * on is not fought, and switching the theme back to vanilla puts the fog
	 * exactly where the player left it.
	 */
	private void applyFog() {
		if (!fog.value()) {
			releaseFog();
			return;
		}
		// The accessor lives in a mixin: if it ever fails to apply, holding fog
		// down is not worth a crash on every frame of the sky.
		try {
			if (!fogHeld) {
				fogWasEnabled = FogRendererAccessor.capy$fogEnabled();
				fogHeld = true;
			}
			if (FogRendererAccessor.capy$fogEnabled()) {
				FogRendererAccessor.capy$setFogEnabled(false);
			}
		} catch (Throwable e) {
			// The accessor is a mixin. Before it applies - or if it never does -
			// reading the field throws an {@link AssertionError}, which is not a
			// RuntimeException: leaving it uncaught took the whole frame down.
			fogHeld = false;
			com.capy.client.util.Guard.report("capy sky fog", e);
		}
	}

	private void releaseFog() {
		if (!fogHeld) {
			return;
		}
		fogHeld = false;
		try {
			FogRendererAccessor.capy$setFogEnabled(fogWasEnabled);
		} catch (Throwable e) {
			// Nothing to restore through; vanilla keeps whatever state it has.
			com.capy.client.util.Guard.report("capy sky fog release", e);
		}
	}

	private void draw(Canvas c, int index, float w, float h, float alpha) {
		switch (index) {
			case BLACK_HOLE -> blackHole(c, w, h, alpha);
			case SPACE -> space(c, w, h, alpha);
			case AURORA -> aurora(c, w, h, alpha);
			case SUNSET -> sunset(c, w, h, alpha);
			case DEEP_OCEAN -> deepOcean(c, w, h, alpha);
			case CHERRY -> cherryBlossom(c, w, h, alpha);
			case VAPORWAVE -> vaporwave(c, w, h, alpha);
			case VOID -> voidSky(c, w, h, alpha);
			case ECLIPSE -> eclipse(c, w, h, alpha);
			default -> {
			}
		}
	}

	// ---------------------------------------------------------------- themes

	/**
	 * A supermassive black hole, seen from just above the plane of its disc.
	 *
	 * The disc is a stack of thin annuli, each split into forty arcs: the colour
	 * runs from white hot at the inner edge to dull iron at the rim, and every
	 * arc is brighter on the side coming towards the camera and dimmer on the
	 * side going away. That asymmetry is relativistic beaming, and it is what
	 * makes a real accretion disc read as turning rather than painted. The
	 * shadow is feathered instead of cut out, the photon ring is a hairline of
	 * light bent around it, and the stars behind are reddened and smeared into
	 * arcs as they approach the lens.
	 */
	private void blackHole(Canvas c, float w, float h, float alpha) {
		float u = h / 1080f;
		float base = Math.min(w, h);
		// Fixed in the sky, at compass azimuth 0 and 26 degrees up: low enough to
		// sit over the horizon, high enough to be looked up at.
		// Fourteen degrees up: high enough to read as "in the sky", low enough
		// that the disc is not clipped by the top of the frame at the default
		// pitch, which is where a player sees it first.
		float[] sky = skyPoint(skyOriginYaw, 14f);
		float cx = sky[0];
		float cy = sky[1];
		float hole = base * 0.108f;
		// The disc's inner edge sits close to the shadow: the gap between a round
		// shadow and a disc this flat is a wedge, and the wider it is the more it
		// reads as a hole in the artwork rather than as the hole in space.
		float inner = base * 0.131f;
		float outer = base * 0.610f;
		float tilt = 0.32f;
		float flow = spin * 0.55f;

		c.gradient(0f, 0f, w, h,
				a(0xFF0D0719, alpha), a(0xFF0A0514, alpha), a(0xFF06030D, alpha),
				a(0xFF030107, alpha), a(0xFF000000, alpha));

		// Dust clouds far behind, and the faint glow of a galaxy off to one side.
		for (int i = 0; i < 5; i++) {
			float gx = (0.08f + noise(i, 210) * 0.84f) * w;
			float gy = (0.06f + noise(i, 211) * 0.88f) * h;
			float gr = base * (0.16f + noise(i, 212) * 0.16f);
			int tint = mix(0xFF3C1A70, 0xFF14325E, noise(i, 213));
			c.glow(gx, gy, gr, gr * 0.66f, a(tint, alpha * 0.085f), 3, 22);
		}

		// Stars: dimmed, reddened and bent into tangential arcs near the shadow.
		if (stars.value()) {
			for (int i = 0; i < 160; i++) {
				float x = noise(i, 201) * w;
				float y = noise(i, 202) * h;
				float dx = x - cx;
				float dy = y - cy;
				float distance = (float) Math.sqrt(dx * dx + dy * dy);
				float rel = distance / (base * 0.62f);
				if (rel < 0.14f) {
					continue;
				}
				float lens = 1f - Math.min(1f, Math.max(0f, (rel - 0.14f) * 1.9f));
				float size = Math.max(1f, u * (0.8f + noise(i, 203) * 1.7f));
				float twinkle = 0.55f + 0.45f
						* (float) Math.sin(time * (0.5f + noise(i, 204) * 1.6f) + noise(i, 205) * TWO_PI);
				int tint = mix(starTint(i), 0xFFFFB07A, lens * 0.60f);
				// The stars are reddened and stretched thin, never drawn as arcs.
				// An arc at a star's radius is a quarter circle of hairlines; a
				// dozen of them read as scratches on the lens, not as lensing.
				c.quad(x, y, x + size, y + size,
						a(tint, alpha * 0.70f * twinkle), a(tint, alpha * 0.70f * twinkle));
			}
		}

		// The disc: one whole tilted annulus, the direct image.
		//
		// Whole, not half. A half annulus ends on a horizontal chord across the
		// sky, and that chord is unmistakable - it reads as a semicircle pasted
		// on to the background rather than as a disc seen at an angle. The parts
		// that belong in front of the shadow are drawn later, after the shadow.
		float breathe = 0.5f + 0.5f * (float) Math.sin(time * 0.45f);
		discHalf(c, cx, cy, inner, outer, tilt, 0f, TWO_PI, alpha, 1f);
		// The white hot inner edge, bleeding a little into the space above it.
		c.glow(cx, cy, inner * 1.20f, inner * 1.20f * tilt,
				a(0xFFFFF4DE, alpha * 0.11f), 6, 26);
		// The second image: the far side of the disc bent up over the crown of
		// the shadow. This arc is what says the light has been round the hole;
		// without it the shadow is a ball with a ring behind it.
		c.annulusArc(cx, cy, hole * 1.055f, hole * 1.30f, 0.94f,
				(float) Math.PI * 0.92f, (float) Math.PI * 2.08f,
				a(0xFFFFE9C4, alpha * 0.42f), a(0xFFFFB25A, alpha * 0.05f), 4);
		c.annulusArc(cx, cy, hole * 1.040f, hole * 1.105f, 0.98f,
				(float) Math.PI * 0.96f, (float) Math.PI * 2.04f,
				a(0xFFFFFFFF, alpha * 0.75f), a(0xFFFFD79A, alpha * 0.22f), 2);
		// Beaming: the side turning towards the camera is brighter and whiter.
		// A soft glow over that half of the disc, not a second annulus: an
		// annulus drawn off centre leaves crescents where its edges do not meet.
		c.glow(cx - outer * 0.18f, cy, outer * 0.58f, outer * 0.58f * tilt,
				a(0xFFFFC27A, alpha * 0.24f), 8, 22);
		// Doppler beaming, the other half of the effect: the limb turning away
		// from the camera is reddened and dimmed, so the disc reads as spinning
		// instead of as a painted ellipse.
		c.glow(cx + outer * 0.42f, cy, outer * 0.52f, outer * 0.52f * tilt,
				a(0xFF160300, alpha * 0.42f), 6, 22);
		// The haze the disc throws into the dust around it.
		c.glow(cx, cy, base * 0.92f, base * 0.70f,
				a(0xFF43167E, alpha * (0.040f + 0.018f * breathe)), 16, 22);

		// Hot gas riding round the disc: soft blobs at a handful of radii, each
		// at its own orbital rate. Blobs, not arcs: an arc of even a few pixels
		// width lying across the disc reads as a scratch on the lens.
		for (int i = 0; i < 16; i++) {
			float t = 0.06f + noise(i, 270) * 0.90f;
			float r = inner + (outer - inner) * t;
			float speed = 0.55f + 1.1f * (1f - t);
			float angle = flow * speed + noise(i, 271) * TWO_PI;
			float sx = cx + (float) Math.cos(angle) * r;
			float sy = cy + (float) Math.sin(angle) * r * tilt;
			float sr = base * (0.022f + noise(i, 272) * 0.040f) * (1.10f - t * 0.30f);
			int tint = mix(0xFFFFFFFF, 0xFFFF8A30, t);
			float twinkle = 0.55f + 0.45f * (float) Math.sin(time * 1.9f + noise(i, 273) * TWO_PI);
			c.glow(sx, sy, sr, sr * 0.62f, a(tint, alpha * (0.10f + 0.16f * twinkle)), 4, 20);
		}

		// The event horizon. Everything drawn before this is behind it and
		// everything drawn after is in front of it - that ordering is the whole
		// illusion.
		c.annulusGradient(cx, cy, hole * 0.97f, hole * 0.97f * 0.99f,
				hole * 1.03f, hole * 1.03f * 0.99f, a(0xFF000000, alpha), a(0xFF000000, 0f), 2);
		c.disc(cx, cy, hole * 0.985f, hole * 0.985f, a(0xFF000000, alpha));

		// The near side of the disc, passing in front of the bottom of the
		// shadow. A band and not a half disc: what crosses the shadow is the
		// front of the disc, and drawing the whole near half buries the hole in
		// its own light instead of showing it in silhouette.
		// A thin arc just outside the rim at the bottom, not a band across its
		// middle: an inner radius inside the rim paints a white crescent over the
		// hole itself, which is the one thing a black hole must never show.
		// tilt 1.0, not less: a flatter arc dips inside the rim at the bottom and
		// paints a bright whisker across the middle of the shadow.
		c.annulusArc(cx, cy, hole * 1.00f, hole * 1.26f, 1.0f,
				(float) Math.PI * 0.16f, (float) Math.PI * 0.84f,
				a(0xFFFFFDF4, alpha * 0.80f), a(0xFFFFB45A, alpha * 0.06f), 3);
		c.glow(cx, cy + hole * 0.78f, hole * 1.30f, hole * 0.22f,
				a(0xFFFFDCA8, alpha * 0.22f), 6, 22);

		// The photon ring: the last light that can still orbit, a hair of it
		// wrapped round the shadow, brightest at the front.
		c.ellipseRing(cx, cy, hole * 1.036f, hole * 1.036f * 0.99f,
				hole * 1.004f, hole * 1.004f * 0.99f, a(0xFFFFEFD2, alpha * 0.55f));
		c.arc(cx, cy, hole * 1.040f, hole * 1.040f * 0.99f, hole * 0.012f,
				a(0xFFFFFFFF, alpha * 0.65f), (float) Math.PI * 0.10f, (float) Math.PI * 0.90f);
	}

	/**
	 * Half of an accretion disc: annular bands from the shadow's edge out to the
	 * rim, white hot inside and dull iron outside, over half the circle so the
	 * two halves can be drawn on either side of the shadow.
	 *
	 * @param hole   the radius the bands are measured in multiples of
	 * @param outer  the outer limit, in pixels
	 * @param tiltY  how flat the disc is: 1 is round, 0.3 is seen from above
	 * @param beam   brightness of this half (the near side is beamed towards us)
	 */
	private void discHalf(Canvas c, float cx, float cy, float hole, float outer, float tiltY,
			float from, float to, float alpha, float beam) {
		float[] radius = {1.02f, 1.30f, 1.85f, 3.00f, 5.20f, 9.00f};
		int[] tint = {0xFFFFFFFF, 0xFFFFF2D4, 0xFFFFD08A, 0xFFFF9A38, 0xFFB83A06, 0xFF2A0500};
		float[] weight = {1.00f, 0.97f, 0.86f, 0.62f, 0.34f, 0.08f};
		for (int i = 0; i < radius.length - 1; i++) {
			float r0 = hole * radius[i];
			float r1 = Math.min(outer, hole * radius[i + 1]);
			if (r1 <= r0) {
				continue;
			}
			c.annulusArc(cx, cy, r0, r1, tiltY, from, to,
					a(tint[i], alpha * weight[i] * beam),
					a(tint[i + 1], alpha * weight[i + 1] * beam), 5);
		}
	}

	/**
	 * The night sky: a galaxy running corner to corner, dust lanes across it,
	 * three magnitudes of star, a warm core and a few cold nebulae.
	 */
	private void space(Canvas c, float w, float h, float alpha) {
		float u = h / 1080f;
		float base = Math.min(w, h);
		c.gradient(0f, 0f, w, h,
				a(0xFF01030B, alpha), a(0xFF030718, alpha), a(0xFF050F26, alpha),
				a(0xFF060A1C, alpha), a(0xFF02040C, alpha), a(0xFF000000, alpha));

		float drift = (float) Math.sin(time * 0.035f) * h * 0.008f;
		// The galaxy has a place in the sky, like the black hole and the sun:
		// the band runs corner to corner around bearing 0, so it slides out of
		// the frame as the player turns instead of hanging off the screen.
		float shift = skyX(skyOriginYaw) - w * 0.5f;
		float ax = w * 0.04f + shift;
		float ay = h * 1.02f + drift;
		float bx = w * 0.98f + shift;
		float by = h * -0.04f + drift;

		// The Milky Way: a broad, uneven swath built from many small, soft
		// clouds rather than a few large ones. A handful of big glows read as
		// one grey oval on the sky; a hundred small ones read as a galaxy.
		for (int i = 0; i < 64; i++) {
			float t = noise(i, 320);
			float spread = (noise(i, 321) - 0.5f) * 2f;
			float x = ax + (bx - ax) * t + spread * base * 0.11f;
			float y = ay + (by - ay) * t + spread * base * 0.09f;
			float r = base * (0.045f + noise(i, 322) * 0.050f);
			float density = 1f - Math.min(1f, Math.abs(spread)) * 0.72f;
			int tint = mix(0xFF7A5CE0, 0xFFB8A2F8, noise(i, 323));
			c.glow(x, y, r, r * 0.60f, a(tint, alpha * 0.090f * density), 3, 14);
		}
		// A warm, denser core, where the band is thickest.
		for (int i = 0; i < 22; i++) {
			float t = 0.50f + noise(i, 324) * 0.18f;
			float x = ax + (bx - ax) * t + (noise(i, 325) - 0.5f) * base * 0.12f;
			float y = ay + (by - ay) * t + (noise(i, 326) - 0.5f) * base * 0.10f;
			float r = base * (0.040f + noise(i, 327) * 0.045f);
			c.glow(x, y, r, r * 0.55f, a(0xFFFFC076, alpha * 0.105f), 3, 14);
		}
		// Dust lanes: dark, soft, lying along the band.
		for (int i = 0; i < 10; i++) {
			float t = 0.04f + noise(i, 328) * 0.92f;
			float x = ax + (bx - ax) * t + (noise(i, 311) - 0.5f) * base * 0.09f;
			float y = ay + (by - ay) * t + (noise(i, 312) - 0.5f) * base * 0.04f;
			float r = base * (0.035f + noise(i, 313) * 0.070f);
			float slant = 0.20f + noise(i, 314) * 0.10f;
			c.glow(x, y, r, r * slant, a(0xFF000000, alpha * 0.13f), 3, 14);
		}

		boolean showStars = stars.value();
		// Stars packed into the band, where a galaxy actually has them.
		for (int i = 0; showStars && i < 200; i++) {
			float t = noise(i, 301);
			float off = (noise(i, 302) - 0.5f) * 2f;
			float x = ax + (bx - ax) * t + off * base * 0.16f;
			float y = ay + (by - ay) * t + off * base * 0.13f;
			float near = 1f - Math.min(1f, Math.abs(off));
			star(c, i + 900, x, y, alpha * (0.30f + 0.55f * near), u * 0.95f);
		}
		// The rest of the sky, thinner and drifting very slowly.
		for (int i = 0; showStars && i < 240; i++) {
			float x = noise(i, 305) * w;
			float y = (noise(i, 306) * h + time * (2f + noise(i, 307) * 8f)) % h;
			star(c, i, x, y, alpha * 0.85f, u);
		}
		// Nebulae: cold, faint, breathing on a long cycle.
		for (int i = 0; i < 3; i++) {
			float x = (0.14f + i * 0.35f) * w;
			float y = (0.72f - i * 0.17f) * h;
			float pulse = 0.5f + 0.5f * (float) Math.sin(time * 0.25f + i * 2.1f);
			int tint = i == 0 ? 0xFFFF4FA8 : (i == 1 ? 0xFF4FB4FF : 0xFF9A5CFF);
			c.glow(x, y, base * (0.22f + 0.05f * pulse), base * 0.12f,
					a(tint, alpha * (0.050f + 0.040f * pulse)), 8, 22);
		}
		// The brightest few, with the cross flare a camera gives them.
		for (int i = 0; showStars && i < 6; i++) {
			float x = (0.06f + noise(i, 306) * 0.88f) * w;
			float y = (0.05f + noise(i, 307) * 0.72f) * h;
			float pulse = 0.5f + 0.5f * (float) Math.sin(time * (0.8f + i * 0.22f) + i * 1.7f);
			float radius = Math.max(1.6f, u * (2.0f + 2.4f * pulse));
			int color = a(0xFFDCEBFF, alpha * (0.45f + 0.5f * pulse));
			c.disc(x, y, radius, radius, color);
			float arm = radius * (3.4f + 2.6f * pulse);
			c.seg(x - arm, y, x + arm, y, Math.max(1f, 0.9f * u),
					a(0xFFDCEBFF, alpha * 0.32f * pulse));
			c.seg(x, y - arm, x, y + arm, Math.max(1f, 0.9f * u),
					a(0xFFDCEBFF, alpha * 0.32f * pulse));
			c.glow(x, y, radius * 4f, radius * 4f, a(0xFF9EC8FF, alpha * 0.10f * pulse), 2, 16);
		}

		shootingStar(c, w, h, alpha);
	}

	/**
	 * One star: magnitude sets the size, the index sets the colour temperature,
	 * and the twinkle rate belongs to the star rather than to the frame.
	 */
	private void star(Canvas c, int seed, float x, float y, float alpha, float u) {
		// One wind for the whole sky: every star field drifts on the same slow
		// curve, so the sky reads as one place instead of as layers sliding
		// past each other. A few pixels over a minute is all it takes to stop
		// a star field looking like a still image.
		x += (float) Math.sin(time * 0.045f) * frameW * 0.004f;
		y += (float) Math.cos(time * 0.031f) * frameH * 0.004f;
		// And they pan with the view: a star field that ignores the camera is a
		// decal, not a sky. The rate is the frame's own horizontal scale, worked
		// out from the current field of view, so the field slides at exactly the
		// rate the terrain does and the stars stay where they belong when the
		// player turns.
		float pixelsPerDegree = (frameW * 0.5f) / tanHalfH * 0.0174533f;
		x -= wrapDegrees(viewYaw) * pixelsPerDegree;
		x = x % frameW;
		if (x < 0f) {
			x += frameW;
		}
		// And vertically: the field is a tile, so it wraps rather than running
		// out of stars when the view swings past the edge of it.
		y += skyPitch;
		y = y % frameH;
		if (y < 0f) {
			y += frameH;
		}
		float magnitude = noise(seed, 207);
		float size = Math.max(1f, u * (0.7f + magnitude * magnitude * 4.0f));
		float twinkle = 0.55f + 0.45f
				* (float) Math.sin(time * (0.35f + noise(seed, 204) * 2.1f) + noise(seed, 205) * TWO_PI);
		int tint = starTint(seed);
		float bright = alpha * (0.30f + 0.70f * magnitude) * (0.55f + 0.45f * twinkle);
		if (size < 1.6f) {
			c.quad(x, y, x + 1f, y + 1f, a(tint, bright), a(tint, bright));
			return;
		}
		if (magnitude > 0.72f) {
			c.glow(x, y, size * 2.6f, size * 2.6f, a(tint, bright * 0.22f), 2, 14);
		}
		c.disc(x, y, size, size, a(tint, bright));
	}

	/** Star colour temperature: mostly white, a few blue and a few warm. */
	private static int starTint(int index) {
		float pick = noise(index, 111);
		if (pick < 0.16f) {
			return 0xFFBBD4FF;
		}
		if (pick < 0.40f) {
			return 0xFFFFFFFF;
		}
		if (pick < 0.82f) {
			return 0xFFFFF3DA;
		}
		return 0xFFFFCDB4;
	}

	/**
	 * A shooting star: a tapered tail of six segments, a bright head and a soft
	 * halo, thrown low across the sky once every few seconds.
	 */
	private void shootingStar(Canvas c, float w, float h, float alpha) {
		if (!stars.value()) {
			return;
		}
		float u = h / 1080f;
		float life = 1.3f;
		float age = time - shootingStarAt;
		if (shootingStarAt < 0f || age >= life) {
			if (time < nextShootingStar) {
				return;
			}
			shootingStarAt = time;
			shootingStarX = random.nextFloat() * w * 0.7f;
			shootingStarY = random.nextFloat() * h * 0.30f;
			shootingStarAngle = 0.35f + random.nextFloat() * 0.35f;
			nextShootingStar = time + 7f + random.nextFloat() * 11f;
			age = 0f;
		}
		if (age < 0f) {
			return;
		}
		float fade = age < 0.22f ? age / 0.22f : Math.max(0f, 1f - (age - 0.22f) / (life - 0.22f));
		if (fade <= 0f) {
			return;
		}
		float cos = (float) Math.cos(shootingStarAngle);
		float sin = (float) Math.sin(shootingStarAngle);
		float headX = shootingStarX + age * w * 0.30f;
		float headY = shootingStarY + age * h * 0.20f;
		float stretch = 0.35f + 0.65f * Math.min(1f, age / 0.35f);
		float tail = h * 0.075f * stretch;
		for (int i = 0; i < 7; i++) {
			float from = i / 7f;
			float to = (i + 1f) / 7f;
			c.seg(headX - cos * tail * from, headY - sin * tail * from,
					headX - cos * tail * to, headY - sin * tail * to,
					Math.max(1f, u * (2.8f - 2.2f * from)),
					a(0xFFFFFFFF, alpha * fade * (0.75f - 0.10f * i)));
		}
		c.disc(headX, headY, Math.max(1.4f, 1.8f * u), Math.max(1.4f, 1.8f * u),
				a(0xFFFFFFFF, alpha * fade));
		c.glow(headX, headY, h * 0.022f, h * 0.022f, a(0xFFCFE4FF, alpha * 0.22f * fade), 2, 14);
	}

	/**
	 * Northern lights over a dark ridge.
	 *
	 * Each curtain is a run of narrow vertical strips with three layers: a faint
	 * tail above, the bright hem at the bottom, and a short fade under it. The
	 * strips vary in brightness along the curtain, which is what reads as the
	 * vertical rays of a real aurora, and each curtain drifts, waves and breathes
	 * on its own phase.
	 */
	private void aurora(Canvas c, float w, float h, float alpha) {
		c.gradient(0f, 0f, w, h,
				a(0xFF01040C, alpha), a(0xFF040A1C, alpha), a(0xFF06121F, alpha),
				a(0xFF030711, alpha), a(0xFF010208, alpha), a(0xFF000000, alpha));
		if (stars.value()) {
			for (int i = 0; i < 170; i++) {
				float x = noise(i, 401) * w;
				float y = noise(i, 402) * h * 0.82f;
				star(c, i + 400, x, y, alpha * 0.65f, h / 1080f);
			}
		}

		int[] hems = {0xFF3BFFA8, 0xFF45FFD2, 0xFF4FC8FF, 0xFF9C7BFF};
		float strip = Math.max(2f, w / 320f);
		// The curtain hangs at the theme's bearing: its waves and its brightest
		// rays are sampled from a coordinate that moves with the sky, so the
		// same fold of the aurora stays over the same part of the horizon as
		// the player turns, instead of the whole curtain being redrawn on the
		// screen in the same place.
		float anchor = skyX(skyOriginYaw) - w * 0.5f;
		for (int band = 0; band < hems.length; band++) {
			int hem = hems[band];
			// The curtain hangs over the horizon, so it moves down the frame as
			// the player looks up - the same travel the terrain makes.
			float baseY = h * (0.34f + band * 0.070f) + skyPitch;
			float amplitude = h * (0.052f + band * 0.004f);
			float height = h * (0.24f - band * 0.028f);
			float depth = 1f - band * 0.16f;
			int clear = a(hem, 0f);
			int slice = 0;
			for (float x = -strip; x < w + strip; x += strip, slice++) {
				float sx = x - anchor;
				float y = baseY
						+ (float) Math.sin(sx * 0.0038f + time * 0.42f + band * 1.3f) * amplitude
						+ (float) Math.sin(sx * 0.0125f - time * 0.95f + band * 2.4f) * amplitude * 0.32f;
				// Three rates of shimmer: a fine one per ray, a slower one along the
				// curtain, and a fixed offset per slice so neighbouring rays differ.
				float ray = 0.5f + 0.5f
						* (float) Math.sin(sx * 0.16f + time * 1.7f + band * 1.7f);
				float swell = 0.5f + 0.5f
						* (float) Math.sin(sx * 0.055f - time * 1.1f + band * 2.2f);
				float grain = noise(slice, 430 + band);
				float beams = 0.30f + 0.70f * (0.45f * ray + 0.35f * swell + 0.20f * grain);
				float column2 = 0.5f + 0.5f
						* (float) Math.sin(sx * 0.008f - time * 0.55f + band * 2.9f);
				float top = height * (0.42f + 0.34f * column2 + 0.24f * beams);
				int high = a(hem, alpha * depth * (0.008f + 0.030f * beams));
				int mid = a(mix(hem, 0xFFFFFFFF, 0.25f),
						alpha * depth * (0.045f + 0.155f * beams));
				int edge = a(mix(hem, 0xFFFFFFFF, 0.66f),
						alpha * depth * (0.14f + 0.40f * beams));
				c.quad(x, y - top, x + strip, y - top * 0.30f, clear, high);
				c.quad(x, y - top * 0.30f, x + strip, y - height * 0.05f, high, mid);
				c.quad(x, y - height * 0.05f, x + strip, y, mid, edge);
				c.quad(x, y, x + strip, y + height * 0.10f, edge, clear);
			}
		}

		// Light the curtains spill onto the air near the horizon.
		c.glow(w * 0.50f, h * 0.93f, w * 0.80f, h * 0.055f,
				a(0xFF3BFFA8, alpha * 0.10f), 4, 24);
		c.glow(w * 0.28f, h * 0.90f, w * 0.40f, h * 0.040f,
				a(0xFF9C7BFF, alpha * 0.07f), 3, 20);

		// Land, black against the glow, and a rim of light on the far ridge.
		c.glow(w * 0.5f, h * 0.99f, w * 0.75f, h * 0.11f, a(0xFF1E6B52, alpha * 0.15f), 4, 22);
		ridge(c, w, h, h * 0.952f, h * 0.075f, 0xFF02040A, alpha, 71);
		ridge(c, w, h, h * 0.984f, h * 0.045f, 0xFF000000, alpha, 72);
		c.quad(0f, h * 0.944f, w, h * 0.952f, a(0xFF7CFFC8, 0f), a(0xFF7CFFC8, alpha * 0.10f));
		vignette(c, w, h, alpha, 0.30f);
	}

	/**
	 * A sun going down over water: an eleven stop sky, lenticular clouds lit from
	 * below, a limb-darkened disc with its halo, three ridgelines fading into
	 * haze, and the sun's path breaking into glints on the swell.
	 */
	private void sunset(Canvas c, float w, float h, float alpha) {
		float u = h / 1080f;
		float base = Math.min(w, h);
		// The horizon is a place in the world, not a line on the screen: it sinks
		// as the view rises.
		float horizon = h * 0.695f + skyPitch;
		float sea = h - horizon;

		c.gradient(0f, 0f, w, horizon,
				a(0xFF160B36, alpha), a(0xFF2A1148, alpha), a(0xFF47174F, alpha),
				a(0xFF6B2358, alpha), a(0xFF94305C, alpha), a(0xFFBE4355, alpha),
				a(0xFFDE5F4A, alpha), a(0xFFF2854B, alpha), a(0xFFFFA952, alpha),
				a(0xFFFFC97E, alpha), a(0xFFFFDCA0, alpha));

		// The sun sets in the west. Only its azimuth is anchored: a sunset is
		// tied to the horizon, so it may not climb when the player looks up.
		float sunX = skyX(skyOriginYaw);
		float sunY = horizon - h * 0.155f + (float) Math.sin(time * 0.18f) * h * 0.006f;
		float sunR = base * 0.086f;

		// Clouds before the sun: lit underneath, dark on top, drifting.
		for (int i = 0; i < 7; i++) {
			float span = w * (0.26f + noise(i, 51) * 0.30f);
			float speed = 4f + i * 2.2f;
			float cx = ((time * speed + noise(i, 52) * w * 1.6f) % (w + span * 1.8f)) - span * 0.9f;
			float cy = horizon * (0.12f + i * 0.100f);
			float thickness = horizon * (0.010f + noise(i, 53) * 0.009f);
			float fade = 0.55f + 0.45f * (float) Math.sin(time * 0.30f + i * 1.4f);
			int lit = mix(0xFFFFC88E, 0xFFFF8A5A, i / 6f);
			int shade = mix(0xFF7A2A56, 0xFF3A1238, i / 6f);
			cloud(c, cx, cy, span, thickness * 2.4f,
					a(lit, alpha * (0.16f + 0.10f * fade)), a(shade, alpha * (0.13f + 0.07f * fade)));
		}

		// Rays: long, soft, turning slowly and fading with distance.
		for (int i = 0; i < 18; i++) {
			float angle = spin * 0.06f + i * (float) (Math.PI / 9.0);
			float cos = (float) Math.cos(angle);
			float sin = (float) Math.sin(angle);
			float reach = base * (i % 2 == 0 ? 0.66f : 0.42f)
					* (0.85f + 0.15f * (float) Math.sin(time * 1.1f + i * 0.7f));
			for (int step = 0; step < 3; step++) {
				float from = 0.32f + step * 0.24f;
				float to = from + 0.24f;
				float bright = (0.042f - step * 0.011f)
						* (0.5f + 0.5f * (float) Math.sin(time * 1.4f + i * 0.9f + step));
				c.seg(sunX + cos * reach * from, sunY + sin * reach * from,
						sunX + cos * reach * to, sunY + sin * reach * to,
						Math.max(1f, u * (5f - step * 1.3f)), a(0xFFFFB366, alpha * bright));
			}
		}

		c.glow(sunX, sunY, sunR * 6.6f, sunR * 6.6f,
				a(0xFFFF7A3C, alpha * (0.10f + 0.02f * (float) Math.sin(time * 1.2f))), 5, 26);
		c.glow(sunX, sunY, sunR * 3.0f, sunR * 3.0f, a(0xFFFF9A4E, alpha * 0.13f), 4, 22);
		c.glow(sunX, sunY, sunR * 1.55f, sunR * 1.55f, a(0xFFFFC77A, alpha * 0.18f), 3, 18);
		// The disc itself, bright to its edge, then limb darkening as a smooth
		// radial gradient laid over it and a feathered rim against the sky.
		c.disc(sunX, sunY, sunR, sunR, a(0xFFFFC062, alpha));
		c.glow(sunX, sunY, sunR * 0.97f, sunR * 0.97f, a(0xFFFFFDF2, alpha * 0.95f), 7, 44);
		c.discSoft(sunX, sunY, sunR, a(0xFFFFA854, alpha * 0.40f));

		// The sea: a warm surface band, then depth.
		c.gradient(0f, horizon, w, h,
				a(0xFF8A3E48, alpha), a(0xFF52203A, alpha), a(0xFF2A0E24, alpha),
				a(0xFF0C0412, alpha));
		c.glow(sunX, horizon, w * 0.42f, h * 0.020f, a(0xFFFFBE78, alpha * 0.13f), 3, 22);

		// The sun's path: broken by the swell into widening glints.
		int glints = 56;
		for (int i = 0; i < glints; i++) {
			float t = (i + 0.5f) / glints;
			float y = horizon + sea * (t * t * 0.99f + 0.01f) + (noise(i, 65) - 0.5f) * sea * 0.007f;
			float grow = 0.18f + t * 1.9f;
			float half = sunR * grow * (0.30f + 0.40f * noise(i, 64));
			float wobble = (float) Math.sin(time * 1.4f + i * 0.75f) * sunR * 0.32f * t;
			float shimmer = 0.5f + 0.5f * (float) Math.sin(time * 2.6f - i * 0.55f);
			float thick = Math.max(1f, u * (1.2f + t * 6f));
			int tint = mix(0xFFFFE8BC, 0xFFFF7A3A, t);
			c.seg(sunX + wobble - half, y, sunX + wobble + half, y, thick,
					a(tint, alpha * (0.06f + 0.42f * shimmer) * (1f - t * 0.30f)));
		}

		// Three ridgelines, each paler than the one in front of it.
		ridge(c, w, h, horizon + sea * 0.03f, h * 0.082f,
				mix(0xFF3A1540, 0xFF7A3A5A, 0.55f), alpha * 0.80f, 61);
		ridge(c, w, h, horizon + sea * 0.11f, h * 0.050f, 0xFF24102C, alpha, 62);
		ridge(c, w, h, horizon + sea * 0.22f, h * 0.030f, 0xFF0D040F, alpha, 63);
		vignette(c, w, h, alpha, 0.18f);
	}

	/**
	 * Light falling into deep water: a restless surface, shafts that sway, glass
	 * bubbles rising with a highlight on the shoulder, marine snow, and the
	 * occasional distant fish.
	 */
	private void deepOcean(Canvas c, float w, float h, float alpha) {
		float base = Math.min(w, h);
		float u = h / 1080f;
		c.gradient(0f, 0f, w, h,
				a(0xFF0E6480, alpha), a(0xFF0A4A62, alpha), a(0xFF073248, alpha),
				a(0xFF03202F, alpha), a(0xFF01121D, alpha), a(0xFF00070D, alpha),
				a(0xFF000000, alpha));

		// The surface, seen from below: a bright, restless ceiling.
		c.glow(w * 0.5f, -h * 0.02f, w * 0.66f, h * 0.15f, a(0xFFBFF4FF, alpha * 0.18f), 5, 26);
		for (int i = 0; i < 12; i++) {
			float x = noise(i, 601) * w;
			float y = h * (0.004f + noise(i, 602) * 0.070f);
			float radius = base * (0.03f + noise(i, 603) * 0.05f);
			float pulse = 0.5f + 0.5f * (float) Math.sin(time * 1.5f + i * 2.1f);
			c.glow(x, y, radius, radius * 0.55f, a(0xFFD8FBFF, alpha * (0.045f + 0.075f * pulse)), 3, 18);
		}
		// Caustics: the bright lace the surface light makes on the ceiling.
		for (int i = 0; i < 30; i++) {
			float x = (i / 30f) * w + (float) Math.sin(time * 0.8f + i * 1.7f) * w * 0.014f;
			float y = h * (0.012f + 0.030f * noise(i, 615))
					+ (float) Math.sin(x * 0.006f + time * 1.3f) * h * 0.020f;
			float len = w * (0.018f + noise(i, 616) * 0.055f);
			float bright = 0.05f + 0.12f
					* (0.5f + 0.5f * (float) Math.sin(time * 2.1f + i * 1.9f));
			c.seg(x, y, x + len, y + (float) Math.sin(x * 0.010f + time) * h * 0.006f,
					Math.max(1f, u * 2.0f), a(0xFFE6FCFF, alpha * bright));
		}

		// Shafts: a wide feathered wedge, a soft middle and a bright core.
		for (int i = 0; i < 9; i++) {
			float topX = w * (0.06f + i * 0.105f);
			float topHalf = w * (0.016f + noise(i, 604) * 0.028f);
			float sway = (float) Math.sin(time * 0.45f + i * 1.7f) * w * 0.024f;
			float bottomX = w * 0.5f + (topX + sway - w * 0.5f) * 0.70f;
			float bottomHalf = topHalf * 2.2f;
			float pulse = 0.5f + 0.5f * (float) Math.sin(time * 0.65f + i * 1.9f);
			c.poly(topX - topHalf * 2.6f, 0f, topX + topHalf * 2.6f, 0f,
					bottomX + bottomHalf * 2.6f, h, bottomX - bottomHalf * 2.6f, h,
					a(0xFF54D6FF, alpha * (0.010f + 0.010f * pulse)));
			c.poly(topX - topHalf, 0f, topX + topHalf, 0f,
					bottomX + bottomHalf, h * 0.97f, bottomX - bottomHalf, h * 0.97f,
					a(0xFF54D6FF, alpha * (0.026f + 0.028f * pulse)));
			c.poly(topX - topHalf * 0.45f, 0f, topX + topHalf * 0.45f, 0f,
					bottomX + bottomHalf * 0.45f, h * 0.90f, bottomX - bottomHalf * 0.45f, h * 0.90f,
					a(0xFFA8EEFF, alpha * (0.026f + 0.036f * pulse)));
		}

		// Distant fish, barely more than a shape in the blue.
		for (int i = 0; i < 7; i++) {
			float y = h * (0.30f + noise(i, 617) * 0.55f);
			float scale = 0.5f + noise(i, 618);
			float speed = 18f + noise(i, 619) * 34f;
			float x = (noise(i, 620) * w + time * speed) % (w + 240f) - 120f;
			float len = u * 28f * scale;
			int tint = a(0xFF04202C, alpha * 0.55f);
			c.disc(x, y, len * 0.44f, len * 0.19f, tint);
			c.poly(x - len * 0.40f, y,
					x - len * 0.78f, y - len * 0.24f,
					x - len * 0.78f, y + len * 0.24f,
					x - len * 0.40f, y, tint);
		}

		// Bubbles: glass spheres with a highlight on the upper left shoulder.
		int[] tints = {0xFF7CFFE8, 0xFF7CDFFF, 0xFFC8FFF6};
		for (int i = 0; i < 80; i++) {
			float depth = noise(i, 605);
			float rise = 8f + depth * 42f;
			float y = h - ((noise(i, 606) * h + time * rise) % (h + h * 0.1f));
			float x = noise(i, 607) * w
					+ (float) Math.sin(time * (0.6f + noise(i, 608) * 0.9f) + i) * w * 0.011f;
			float radius = Math.max(1f, u * (1.4f + depth * 7f));
			int tint = tints[i % tints.length];
			int body = a(tint, alpha * (0.08f + 0.26f * depth));
			c.disc(x, y, radius, radius, body);
			c.ellipseRing(x, y, radius, radius, radius * 0.72f, radius * 0.72f, body);
			c.arc(x, y, radius * 0.80f, radius * 0.80f, Math.max(1f, radius * 0.30f),
					a(0xFFFFFFFF, alpha * (0.18f + 0.45f * depth)),
					(float) Math.PI * 1.05f, (float) Math.PI * 1.62f);
			if (depth > 0.65f) {
				c.glow(x, y, radius * 3.2f, radius * 3.2f, a(tint, alpha * 0.09f), 2, 14);
			}
		}

		// Marine snow, drifting almost not at all.
		for (int i = 0; i < 150; i++) {
			float x = (noise(i, 609) * w + time * (2f + noise(i, 610) * 6f)) % w;
			float y = (noise(i, 611) * h + time * (1.5f + noise(i, 612) * 4f)) % h;
			float size = Math.max(1f, u * (0.7f + noise(i, 613) * 1.2f));
			float twinkle = 0.4f + 0.6f
					* (0.5f + 0.5f * (float) Math.sin(time * 1.3f + noise(i, 614) * TWO_PI));
			c.quad(x, y, x + size, y + size,
					a(0xFFDFF9FF, alpha * 0.20f * twinkle), a(0xFFDFF9FF, alpha * 0.20f * twinkle));
		}

		c.gradient(0f, h * 0.55f, w, h, a(0xFF000000, 0f), a(0xFF000000, alpha * 0.30f),
				a(0xFF000000, alpha * 0.55f));
		vignette(c, w, h, alpha, 0.35f);
	}

	/**
	 * Blossom in the air: a branch reaching in out of focus, bokeh, a warm low
	 * sun and three depths of petals tumbling at their own speeds.
	 */
	private void cherryBlossom(Canvas c, float w, float h, float alpha) {
		float base = Math.min(w, h);
		float u = h / 1080f;
		c.gradient(0f, 0f, w, h,
				a(0xFF63B6E8, alpha), a(0xFF9CD2F2, alpha), a(0xFFD9C6E6, alpha),
				a(0xFFFFC4DC, alpha), a(0xFFFFB2C6, alpha));

		float[] sky = skyPoint(skyOriginYaw, 62f);
		float sunX = sky[0];
		float sunY = sky[1];
		float breathe = 0.5f + 0.5f * (float) Math.sin(time * 0.5f);
		c.glow(sunX, sunY, base * 0.56f, base * 0.56f,
				a(0xFFFFD0E4, alpha * (0.20f + 0.09f * breathe)), 5, 26);
		c.glow(sunX, sunY, base * 0.20f, base * 0.20f, a(0xFFFFF2F8, alpha * 0.42f), 4, 20);
		c.disc(sunX, sunY, base * 0.072f, base * 0.072f, a(0xFFFFFFFF, alpha));
		c.discSoft(sunX, sunY, base * 0.074f, a(0xFFFFE8F2, alpha * 0.55f));

		// Shafts of light through the canopy.
		for (int i = 0; i < 3; i++) {
			float spread = w * (0.07f + i * 0.03f);
			float sway = (float) Math.sin(time * 0.35f + i * 1.9f) * w * 0.02f;
			c.poly(sunX - spread + sway, sunY, sunX + spread + sway, sunY,
					sunX + spread * 3.4f, h, sunX - spread * 0.6f, h,
					a(0xFFFFF6E8, alpha * 0.045f));
		}

		// A branch reaching in from the top left, tapering as it goes, with two
		// twigs and a few clusters of blossom on it.
		float px = -w * 0.03f;
		float py = h * 0.02f;
		for (int i = 0; i < 7; i++) {
			float nx = px + w * (0.030f + noise(i, 629) * 0.018f);
			float ny = py + h * (0.012f + noise(i, 630) * 0.016f);
			c.seg(px, py, nx, ny, Math.max(1.5f, u * (15f - i * 1.9f)), a(0xFF3D2338, alpha * 0.40f));
			if (i == 2 || i == 5) {
				float tx = nx + w * 0.045f;
				float ty = ny - h * 0.028f;
				c.seg(nx, ny, tx, ty, Math.max(1.2f, u * 6f), a(0xFF3D2338, alpha * 0.35f));
				for (int k = 0; k < 4; k++) {
					c.glow(tx + (noise(i * 5 + k, 631) - 0.5f) * w * 0.05f,
							ty + (noise(i * 5 + k, 632) - 0.5f) * h * 0.04f,
							u * 13f, u * 9f, a(0xFFFFCBDF, alpha * 0.26f), 5, 16);
				}
			}
			px = nx;
			py = ny;
		}

		// Petals right in front of the lens, so large they are only colour.
		for (int i = 0; i < 8; i++) {
			float travel = (noise(i, 623) * h + time * (26f + noise(i, 624) * 22f)) % (h * 1.2f);
			float x = noise(i, 625) * w + (float) Math.sin(time * 0.5f + i) * w * 0.03f;
			float radius = base * (0.020f + noise(i, 626) * 0.030f);
			c.disc(x, travel, radius, radius * 0.7f, a(0xFFFFC8DC, alpha * 0.10f));
			c.disc(x, travel, radius * 0.55f, radius * 0.4f, a(0xFFFFE2EC, alpha * 0.08f));
		}

		// Three layers: far petals are small, slow and pale; near ones are big,
		// fast and catch the light as they tumble.
		for (int layer = 0; layer < 3; layer++) {
			float scale = 0.55f + layer * 0.35f;
			float speed = 1f + layer * 0.55f;
			int count = 26;
			for (int i = 0; i < count; i++) {
				int seed = layer * 1000 + i;
				float fall = (10f + noise(seed, 601) * 22f) * speed;
				float y = (noise(seed, 602) * h + time * fall) % h;
				float x = noise(seed, 603) * w
						+ (float) Math.sin(time * (0.7f + noise(seed, 604) * 0.8f) * speed
								+ noise(seed, 605) * TWO_PI) * w * 0.035f;
				float angle = time * (0.5f + noise(seed, 606) * 1.4f) * speed + noise(seed, 607) * TWO_PI;
				float tumble = time * (1.2f + noise(seed, 608) * 1.8f) + noise(seed, 609) * TWO_PI;
				float length = Math.max(2.5f, u * (5f + noise(seed, 610) * 5f) * scale);
				float width = length * (0.40f + 0.32f * Math.abs((float) Math.cos(tumble)));
				int tint = noise(seed, 611) < 0.5f ? 0xFFFFB7CE : 0xFFFF9FBE;
				int color = a(mix(tint, 0xFFFFFFFF, 0.15f + 0.35f * noise(seed, 612)),
						alpha * (0.55f + 0.35f * noise(seed, 613)));
				petal(c, x, y, angle, length, width, color);
				if (layer == 2) {
					float shade = Math.max(0f, (float) Math.cos(tumble));
					if (shade > 0.15f) {
						petal(c, x - width * 0.25f, y - width * 0.25f, angle,
								length * 0.72f, width * 0.6f,
								a(0xFFFFFFFF, alpha * 0.20f * shade));
					}
				}
			}
		}
	}

	/** One petal: a leaf-shaped quad (a diamond with a soft waist). */
	private static void petal(Canvas c, float x, float y, float angle, float length, float width,
			int argb) {
		float cos = (float) Math.cos(angle);
		float sin = (float) Math.sin(angle);
		float tip = length;
		float side = width;
		c.poly(x + tip * sin, y - tip * cos,
				x + side * cos, y + side * sin,
				x - tip * sin, y + tip * cos,
				x - side * cos, y - side * sin, argb);
	}

	/**
	 * The retro sunset: a banded sun behind a haze, a mountain skyline, a grid
	 * with real perspective and the scanlines of a CRT.
	 */
	private void vaporwave(Canvas c, float w, float h, float alpha) {
		float base = Math.min(w, h);
		float u = h / 1080f;
		float horizon = h * 0.62f + skyPitch;

		c.gradient(0f, 0f, w, horizon,
				a(0xFF12002A, alpha), a(0xFF2E0040, alpha), a(0xFF5C0057, alpha),
				a(0xFF9A0060, alpha), a(0xFFE0006A, alpha), a(0xFFFF006E, alpha));
		c.quad(0f, horizon, w, h, a(0xFF1B0030, alpha), a(0xFF05000A, alpha));

		float sunX = skyX(skyOriginYaw);
		float sunY = horizon - h * 0.12f;
		float sunR = base * 0.165f;

		c.glow(sunX, sunY, sunR * 2.6f, sunR * 2.6f, a(0xFFFF2A8A, alpha * 0.24f), 5, 26);
		c.glow(sunX, sunY, sunR * 1.25f, sunR * 1.25f, a(0xFFFFE07A, alpha * 0.16f), 3, 18);
		// The disc, banded from yellow at the top to deep pink at the bottom.
		int discBands = 30;
		for (int i = 0; i < discBands; i++) {
			float t0 = i / (float) discBands;
			float t1 = (i + 1f) / discBands;
			float y0 = sunY - sunR + 2f * sunR * t0;
			float y1 = sunY - sunR + 2f * sunR * t1;
			float half0 = (float) Math.sqrt(Math.max(0f, 1f - (2f * t0 - 1f) * (2f * t0 - 1f))) * sunR;
			float half1 = (float) Math.sqrt(Math.max(0f, 1f - (2f * t1 - 1f) * (2f * t1 - 1f))) * sunR;
			int color = mix(0xFFFFE45C, 0xFFFF3C8C, t0);
			c.poly(sunX - half0, y0, sunX + half0, y0, sunX + half1, y1, sunX - half1, y1,
					a(color, alpha));
		}
		// Cut stripes, only below the middle, scrolling down and out of the disc.
		int cut = a(0xFF1A0033, alpha);
		float scroll = (time * u * 11f) % (u * 14f);
		for (int i = 0; i < 8; i++) {
			float t = i / 8f;
			float offset = sunR * (0.06f + 0.94f * t);
			float thickness = u * (4.0f + 13f * t);
			float top = sunY + offset + scroll;
			float bottom = Math.min(top + thickness, sunY + sunR);
			top = Math.max(top, sunY - sunR);
			if (bottom <= top) {
				continue;
			}
			float worst = Math.max(Math.abs(top - sunY), Math.abs(bottom - sunY));
			if (worst >= sunR) {
				continue;
			}
			float half = (float) Math.sqrt(sunR * sunR - worst * worst);
			c.quad(sunX - half, top, sunX + half, bottom, cut, cut);
		}

		// The sea, covering the bottom of the sun.
		c.quad(0f, horizon, w, h, a(0xFF1B0030, alpha), a(0xFF05000A, alpha));

		// Mountains either side, silhouetted against the glow.
		int mountain = a(0xFF2A0038, alpha * 0.95f);
		float[] peaks = {0.0f, 0.10f, 0.055f, 0.155f, 0.075f, 0.0f};
		for (int i = 0; i < peaks.length - 1; i++) {
			float x0 = w * (0.06f + i * 0.070f);
			float x1 = w * (0.06f + (i + 1) * 0.070f);
			c.poly(x0, horizon + sunR * 0.10f, x0, horizon - h * peaks[i] - sunR * 0.05f,
					x1, horizon - h * peaks[i + 1] - sunR * 0.05f, x1, horizon + sunR * 0.10f, mountain);
			float mx0 = w - x0;
			float mx1 = w - x1;
			c.poly(mx0, horizon + sunR * 0.10f, mx0, horizon - h * peaks[i] - sunR * 0.05f,
					mx1, horizon - h * peaks[i + 1] - sunR * 0.05f, mx1, horizon + sunR * 0.10f, mountain);
		}

		c.seg(0f, horizon, w, horizon, Math.max(2f, u * 3f), a(0xFFFF6ECF, alpha * 0.60f));

		// Grid: rows scroll towards the viewer with perspective spacing.
		float span = h - horizon;
		for (int i = 0; i < 16; i++) {
			float t = (i * 20f + (time * 15f) % 20f) / (16f * 20f);
			float y = horizon + span * t * t;
			float fade = 0.10f + 0.62f * t;
			c.seg(0f, y, w, y, Math.max(2f, u * 4f), a(0xFF20F0FF, alpha * fade * 0.32f));
			c.seg(0f, y, w, y, Math.max(1f, u * 1.6f), a(0xFFB0FAFF, alpha * fade));
		}
		for (int i = 0; i < 15; i++) {
			float bx = w * 0.5f + (i - 7) * w * 0.070f;
			c.seg(w * 0.5f, horizon, bx, h, Math.max(2f, u * 3.4f), a(0xFF20F0FF, alpha * 0.18f));
			c.seg(w * 0.5f, horizon, bx, h, Math.max(1f, u * 1.4f), a(0xFFB0FAFF, alpha * 0.52f));
		}

		// Stars above the horizon.
		for (int i = 0; stars.value() && i < 80; i++) {
			float x = noise(i, 630) * w;
			float y = noise(i, 631) * horizon * 0.92f;
			star(c, i + 630, x, y, alpha * 0.60f, u);
		}

		// Scanlines, the way a CRT draws every other row.
		float line = Math.max(2f, u * 4f);
		for (float y = 0f; y < h; y += line) {
			c.seg(0f, y, w, y, Math.max(1f, u * 1.4f), a(0xFF000000, alpha * 0.05f));
		}
	}

	/**
	 * A void: a violet well at the centre, dust on slow orbits, wisps of nebula
	 * and a darkness that closes in from every edge.
	 */
	private void voidSky(Canvas c, float w, float h, float alpha) {
		float base = Math.min(w, h);
		float u = h / 1080f;
		// The well is a place in the sky, not a layer of the HUD: at bearing 156
		// it is behind the player when they face the black hole, and the whole
		// body leaves the frame when they turn away from it.
		float[] sky = skyPoint(skyOriginYaw, 20f);
		float cx = sky[0];
		float cy = sky[1];
		float maxR = (float) Math.sqrt(w * w + h * h) * 0.5f;
		float depth = 0.5f + 0.5f * (float) Math.sin(time * 0.22f);

		c.quad(0f, 0f, w, h, a(0xFF000000, alpha), a(0xFF000000, alpha));

		// The well: many overlapping discs rather than a few rings, because a
		// ring boundary of even a few percent alpha is visible against black.
		c.glow(cx, cy, maxR * (0.92f + 0.04f * depth), maxR * (0.92f + 0.04f * depth),
				a(0xFF6A34B0, alpha * 0.90f), 14, 30);
		c.glow(cx, cy, maxR * (0.54f + 0.05f * depth), maxR * (0.54f + 0.05f * depth),
				a(0xFF8A50D8, alpha * 0.55f), 10, 26);
		// A darker core: the deepest point of a well is not its brightest.
		c.glow(cx, cy, maxR * 0.26f, maxR * 0.20f, a(0xFF05010A, alpha * 0.60f), 8, 26);
		// Wisps of nebula, drifting and breathing.
		for (int i = 0; i < 7; i++) {
			float angle = spin * (0.05f + i * 0.008f) + i * 1.4f;
			float orbit = base * (0.20f + i * 0.075f);
			float x = cx + (float) Math.cos(angle) * orbit;
			float y = cy + (float) Math.sin(angle) * orbit * 0.70f;
			float pulse = 0.5f + 0.5f * (float) Math.sin(time * 0.30f + i * 1.7f);
			c.glow(x, y, base * (0.26f + noise(i, 645) * 0.12f),
					base * (0.08f + noise(i, 646) * 0.05f),
					a(0xFF6A3AB0, alpha * (0.035f + 0.030f * pulse)), 3, 20);
		}

		// Two slow spiral arms of brighter dust, so the well is not a flat orb.
		for (int arm = 0; arm < 2; arm++) {
			for (int i = 0; i < 46; i++) {
				float t = i / 45f;
				float angle = spin * 0.05f + arm * (float) Math.PI + t * 2.6f;
				float radius = base * (0.09f + t * 0.42f);
				float x = cx + (float) Math.cos(angle) * radius;
				float y = cy + (float) Math.sin(angle) * radius * 0.70f;
				float size = Math.max(1f, u * (0.9f + (1f - t) * 2.2f));
				c.quad(x, y, x + size, y + size,
						a(0xFFE0D0FF, alpha * 0.34f * (1f - t * 0.6f)),
						a(0xFFE0D0FF, alpha * 0.34f * (1f - t * 0.6f)));
			}
		}

		// Dust, drifting on a slow orbit.
		for (int i = 0; i < 140; i++) {
			float orbit = noise(i, 640) * TWO_PI + time * (0.02f + noise(i, 641) * 0.06f);
			float radius = base * (0.08f + noise(i, 642) * 0.62f);
			float x = cx + (float) Math.cos(orbit) * radius;
			float y = cy + (float) Math.sin(orbit) * radius * 0.72f;
			float size = Math.max(1f, u * (0.9f + noise(i, 643) * 2.2f));
			float twinkle = 0.35f + 0.65f
					* (0.5f + 0.5f * (float) Math.sin(time * 0.9f + noise(i, 644) * TWO_PI));
			c.quad(x, y, x + size, y + size, a(0xFFF0E8FF, alpha * 0.80f * twinkle),
					a(0xFFF0E8FF, alpha * 0.80f * twinkle));
		}
		// A spark, drawn from the clock's beat so it blinks at the same rate
		// whatever the frame rate is.
		int beat = (int) (time * 5f);
		for (int i = 0; i < 2; i++) {
			if (noise(beat, 650 + i) < 0.55f) {
				continue;
			}
			float x = (0.1f + noise(beat, 660 + i) * 0.8f) * w;
			float y = (0.1f + noise(beat, 670 + i) * 0.8f) * h;
			float size = Math.max(1f, u * 1.6f);
			c.quad(x, y, x + size, y + size, a(0xFFFFFFFF, alpha * 0.85f),
					a(0xFFFFFFFF, alpha * 0.85f));
		}
		vignette(c, w, h, alpha, 0.75f);
	}

	/**
	 * A total eclipse: the moon's shadow, the corona turning around it, the red
	 * chromosphere at the limb, a promontory or two, a diamond of light and the
	 * sky going dark.
	 */
	private void eclipse(Canvas c, float w, float h, float alpha) {
		float base = Math.min(w, h);
		float u = h / 1080f;
		// South, at 34 degrees up: the opposite half of the sky from the black
		// hole, so the two themes cannot sit on top of each other.
		float[] sky = skyPoint(skyOriginYaw, 34f);
		float mx = sky[0];
		float my = sky[1];
		float moonR = base * 0.145f;

		c.gradient(0f, 0f, w, h,
				a(0xFF0A0C16, alpha), a(0xFF0D1020, alpha), a(0xFF07080F, alpha),
				a(0xFF020208, alpha), a(0xFF000000, alpha));
		if (stars.value()) {
			for (int i = 0; i < 120; i++) {
				float x = noise(i, 680) * w;
				float y = noise(i, 681) * h;
				star(c, i + 700, x, y, alpha * 0.45f, u);
			}
		}

		// The corona: near, far, and the long streamers between.
		float breathe = 0.5f + 0.5f * (float) Math.sin(time * 0.5f);
		c.glow(mx, my, moonR * 3.6f, moonR * 3.6f,
				a(0xFFAFC2FF, alpha * (0.065f + 0.020f * breathe)), 14, 26);
		c.glow(mx, my, moonR * 1.85f, moonR * 1.85f,
				a(0xFFD6E2FF, alpha * (0.130f + 0.045f * breathe)), 12, 24);
		c.glow(mx, my, moonR * 1.14f, moonR * 1.14f,
				a(0xFFFFFFFF, alpha * (0.200f + 0.070f * breathe)), 8, 22);

		for (int i = 0; i < 96; i++) {
			float angle = spin * 0.10f + i * (TWO_PI / 96f) + noise(i, 685) * 0.05f;
			float cos = (float) Math.cos(angle);
			float sin = (float) Math.sin(angle);
			float flicker = 0.5f + 0.5f * (float) Math.sin(time * 1.4f + i * 1.07f);
			// A helmet streamer every eighth ray, a shorter one between them.
			float long_ = i % 8 == 0 ? 2.3f : 1f;
			float reach = moonR * (0.30f + 1.15f * flicker) * long_ * (0.7f + noise(i, 686) * 0.6f);
			float taper = 0.55f + noise(i, 687) * 0.45f;
			// A ray is not a straight spoke: each step bends a little further
			// round, and fades fast, which is what makes the corona read as
			// plasma instead of a starburst.
			float bend = (noise(i, 688) - 0.5f) * 0.30f;
			for (int step = 0; step < 4; step++) {
				float from = moonR * 0.995f + reach * (step * 0.25f);
				float to = moonR * 0.995f + reach * ((step + 1) * 0.25f);
				float bright = (0.20f - step * 0.060f) * flicker * taper;
				float skewFrom = bend * step * step * 0.12f;
				float skewTo = bend * (step + 1) * (step + 1) * 0.12f;
				c.seg(mx + cos * from - sin * from * skewFrom, my + sin * from + cos * from * skewFrom,
						mx + cos * to - sin * to * skewTo, my + sin * to + cos * to * skewTo,
						Math.max(1f, u * (4.2f - step * 1.0f) * taper), a(0xFFF2F6FF, alpha * bright));
			}
		}

		// Prominences: small red tongues at the limb.
		for (int i = 0; i < 5; i++) {
			float angle = -2.6f + i * 1.15f + (float) Math.sin(time * 0.2f + i) * 0.06f;
			float len = moonR * (0.05f + noise(i, 690) * 0.07f);
			float px = mx + (float) Math.cos(angle) * moonR;
			float py = my + (float) Math.sin(angle) * moonR;
			c.arc(mx, my, moonR + len, moonR + len, len,
					a(0xFFFF5A3A, alpha * 0.35f), angle - 0.05f, angle + 0.05f);
			c.glow(px, py, len * 1.8f, len * 1.8f, a(0xFFFF6A3A, alpha * 0.16f), 2, 14);
		}

		// Chromosphere: a thin warm ring right at the limb.
		c.ellipseRing(mx, my, moonR * 1.030f, moonR * 1.030f, moonR * 1.000f, moonR * 1.000f,
				a(0xFFFF6A3A, alpha * 0.50f));
		c.ellipseRing(mx, my, moonR * 1.062f, moonR * 1.062f, moonR * 1.030f, moonR * 1.030f,
				a(0xFFFFFFFF, alpha * 0.50f));

		// The diamond ring, swelling and fading on the upper left limb.
		float flare = 0.5f + 0.5f * (float) Math.sin(time * 0.33f);
		float flareAngle = -2.25f;
		float flareX = mx + (float) Math.cos(flareAngle) * moonR * 1.05f;
		float flareY = my + (float) Math.sin(flareAngle) * moonR * 1.05f;
		float flareR = moonR * (0.20f + 0.34f * flare);
		c.glow(flareX, flareY, flareR * 2.4f, flareR * 2.4f,
				a(0xFFFFFFFF, alpha * (0.12f + 0.30f * flare)), 3, 18);
		c.disc(flareX, flareY, Math.max(1.5f, u * (2.5f + 5f * flare)),
				Math.max(1.5f, u * (2.5f + 5f * flare)), a(0xFFFFFFFF, alpha * (0.55f + 0.45f * flare)));

		// The moon: a dark disc with a little of its own surface left in it.
		c.disc(mx, my, moonR, moonR, a(0xFF1B1D27, alpha));
		c.discSoft(mx, my, moonR, a(0xFF14161F, alpha * 0.90f));
		for (int i = 0; i < 6; i++) {
			float angle = noise(i, 691) * TWO_PI;
			float radius = moonR * (0.15f + noise(i, 692) * 0.55f);
			float cr = moonR * (0.07f + noise(i, 693) * 0.16f);
			c.ellipseRing(mx + (float) Math.cos(angle) * radius,
					my + (float) Math.sin(angle) * radius * 0.9f,
					cr, cr * 0.92f, cr * 0.55f, cr * 0.50f, a(0xFF0A0B11, alpha * 0.55f));
		}
		c.disc(mx, my, moonR * 0.985f, moonR * 0.985f, a(0xFF0E1017, alpha * 0.85f));

		// The last veil: the sky itself dims while the sun is covered.
		float veil = alpha * (0.14f + 0.07f * (0.5f + 0.5f * (float) Math.sin(time * 0.28f)));
		c.quad(0f, 0f, w, h, a(0xFF000000, veil), a(0xFF000000, veil));
		vignette(c, w, h, alpha, 0.45f);
	}

	/**
	 * One soft cloud: a lit underside, a darker crown, both feathered. Drawn
	 * with a coarse disc cap, because a cloud is a smudge rather than a shape.
	 */
	private static void cloud(Canvas c, float x, float y, float width, float height,
			int lit, int shade) {
		c.glow(x, y + height * 0.30f, width * 0.50f, height * 0.60f, lit, 3, 18);
		c.glow(x - width * 0.10f, y - height * 0.25f, width * 0.42f, height * 0.55f, shade, 3, 18);
	}

	/**
	 * One jagged skyline: a chain of quads from the ridgeline down to
	 * {@code baseY}, with a deterministic profile.
	 */
	private void ridge(Canvas c, float w, float h, float baseY, float amplitude, int rgb,
			float alpha, int salt) {
		int points = 18;
		float stepX = w / (points - 1f);
		int color = a(rgb, alpha);
		for (int i = 0; i < points - 1; i++) {
			float x0 = i * stepX;
			float x1 = x0 + stepX;
			float y0 = baseY - amplitude * (0.20f + noise(i, salt) * 0.80f);
			float y1 = baseY - amplitude * (0.20f + noise(i + 1, salt) * 0.80f);
			c.poly(x0, y0, x1, y1, x1, baseY, x0, baseY, color);
		}
	}

	/** Darkens the top and bottom edges, the way a lens does. */
	private static void vignette(Canvas c, float w, float h, float alpha, float strength) {
		float shade = alpha * Math.max(0f, Math.min(1f, strength));
		c.gradient(0f, 0f, w, h * 0.24f, a(0xFF000000, shade), a(0xFF000000, 0f));
		c.gradient(0f, h * 0.76f, w, h, a(0xFF000000, 0f), a(0xFF000000, shade * 0.90f));
	}

	// ----------------------------------------------------------------- fences

	private static float base(float w, float h) {
		return Math.min(w, h);
	}

	/** Deterministic noise for index {@code i}, in 0..1. */
	private static float noise(int index, int salt) {
		long value = (long) index * 2654435761L + salt * 0x9E3779B97F4A7C15L;
		value ^= value >>> 33;
		value *= 0xFF51AFD7ED558CCDL;
		value ^= value >>> 33;
		return (value >>> 11) / (float) (1L << 53);
	}

	/** Linear blend of two packed colours, alpha included. */
	private static int mix(int from, int to, float t) {
		float f = Math.max(0f, Math.min(1f, t));
		int a0 = (from >>> 24) & 0xFF;
		int r0 = (from >>> 16) & 0xFF;
		int g0 = (from >>> 8) & 0xFF;
		int b0 = from & 0xFF;
		int a1 = (to >>> 24) & 0xFF;
		int r1 = (to >>> 16) & 0xFF;
		int g1 = (to >>> 8) & 0xFF;
		int b1 = to & 0xFF;
		return (Math.round(a0 + (a1 - a0) * f) << 24)
				| (Math.round(r0 + (r1 - r0) * f) << 16)
				| (Math.round(g0 + (g1 - g0) * f) << 8)
				| Math.round(b0 + (b1 - b0) * f);
	}

	/** The colour, with its own alpha scaled by {@code factor}. */
	private static int a(int argb, float factor) {
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

	// ---------------------------------------------------------------- canvas

	/**
	 * The painting surface: screen pixels in, vertices on the far plane out.
	 */
	private static final class Canvas {
		private final VertexConsumer vc;
		private final PoseStack.Pose pose;
		private final float width;
		private final float height;
		private final double halfW;
		private final double halfH;
		private final float depth;

		Canvas(VertexConsumer vc, PoseStack.Pose pose, float width, float height,
				double halfW, double halfH, float depth) {
			this.vc = vc;
			this.pose = pose;
			this.width = width;
			this.height = height;
			this.halfW = halfW;
			this.halfH = halfH;
			this.depth = depth;
		}

		void vertex(float x, float y, int argb) {
			float nx = x / width * 2f - 1f;
			float ny = 1f - y / height * 2f;
			vc.addVertex(pose, (float) (nx * halfW), (float) (ny * halfH), -depth).setColor(argb);
		}

		void quad(float x0, float y0, float x1, float y1, int top, int bottom) {
			vertex(x0, y0, top);
			vertex(x0, y1, bottom);
			vertex(x1, y1, bottom);
			vertex(x1, y0, top);
		}

		void poly(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3,
				int argb) {
			vertex(x0, y0, argb);
			vertex(x1, y1, argb);
			vertex(x2, y2, argb);
			vertex(x3, y3, argb);
		}

		/**
		 * A vertical gradient through any number of stops: every pair of
		 * neighbouring stops becomes one quad, and the vertex colour
		 * interpolation supplies the blend inside it.
		 */
		void gradient(float x0, float y0, float x1, float y1, int... stops) {
			if (stops.length == 0) {
				return;
			}
			if (stops.length == 1) {
				quad(x0, y0, x1, y1, stops[0], stops[0]);
				return;
			}
			float step = (y1 - y0) / (stops.length - 1f);
			for (int i = 0; i < stops.length - 1; i++) {
				quad(x0, y0 + step * i, x1, y0 + step * (i + 1), stops[i], stops[i + 1]);
			}
		}

		/** A straight run of pixels, expanded into a quad of the given width. */
		void seg(float x0, float y0, float x1, float y1, float thickness, int argb) {
			float dx = x1 - x0;
			float dy = y1 - y0;
			float length = (float) Math.sqrt(dx * dx + dy * dy);
			if (length < 1.0e-4f) {
				return;
			}
			float half = thickness * 0.5f;
			float nx = -dy / length * half;
			float ny = dx / length * half;
			poly(x0 + nx, y0 + ny, x1 + nx, y1 + ny, x1 - nx, y1 - ny, x0 - nx, y0 - ny, argb);
		}

		/** Segments a circle of this size needs to read as round. */
		private static int stepsFor(float rx, float ry, int cap) {
			return Math.max(10, Math.min(cap, Math.round(Math.max(rx, ry) * 0.42f)));
		}

		void disc(float cx, float cy, float rx, float ry, int argb) {
			disc(cx, cy, rx, ry, argb, 160);
		}

		private void disc(float cx, float cy, float rx, float ry, int argb, int cap) {
			if (rx <= 0f || ry <= 0f) {
				return;
			}
			int steps = stepsFor(rx, ry, cap);
			float px = cx + rx;
			float py = cy;
			for (int i = 1; i <= steps; i++) {
				double angle = i * Math.PI * 2.0 / steps;
				float nx = cx + (float) Math.cos(angle) * rx;
				float ny = cy + (float) Math.sin(angle) * ry;
				// The centre repeats, which makes the quad one triangle.
				poly(cx, cy, px, py, nx, ny, cx, cy, argb);
				px = nx;
				py = ny;
			}
		}

		/** The band between two ellipses that share a centre, at even width. */
		void ellipseRing(float cx, float cy, float ox, float oy, float ix, float iy, int argb) {
			if (ox <= 0f || oy <= 0f || ix < 0f || iy < 0f) {
				return;
			}
			int steps = stepsFor(ox, oy, 128);
			float pox = cx + ox;
			float poy = cy;
			float pix = cx + ix;
			float piy = cy;
			for (int i = 1; i <= steps; i++) {
				double angle = i * Math.PI * 2.0 / steps;
				float nox = cx + (float) Math.cos(angle) * ox;
				float noy = cy + (float) Math.sin(angle) * oy;
				float nix = cx + (float) Math.cos(angle) * ix;
				float niy = cy + (float) Math.sin(angle) * iy;
				poly(pix, piy, pox, poy, nox, noy, nix, niy, argb);
				pox = nox;
				poy = noy;
				pix = nix;
				piy = niy;
			}
		}

		/** A turned ring segment, from one angle to another, in radians. */
		void arc(float cx, float cy, float rx, float ry, float thickness, int argb,
				float from, float to) {
			float span = to - from;
			if (Math.abs(span) < 1.0e-4f || rx <= 0f || ry <= 0f) {
				return;
			}
			int steps = stepsFor(rx, ry, 128);
			steps = Math.max(2, Math.min(steps, Math.round(Math.abs(span) / 0.055f)));
			float innerX = Math.max(0f, rx - thickness);
			float innerY = Math.max(0f, ry - thickness);
			float ox = cx + (float) Math.cos(from) * rx;
			float oy = cy + (float) Math.sin(from) * ry;
			float ix = cx + (float) Math.cos(from) * innerX;
			float iy = cy + (float) Math.sin(from) * innerY;
			for (int i = 1; i <= steps; i++) {
				float angle = from + span * i / steps;
				float nox = cx + (float) Math.cos(angle) * rx;
				float noy = cy + (float) Math.sin(angle) * ry;
				float nix = cx + (float) Math.cos(angle) * innerX;
				float niy = cy + (float) Math.sin(angle) * innerY;
				poly(ix, iy, ox, oy, nox, noy, nix, niy, argb);
				ox = nox;
				oy = noy;
				ix = nix;
				iy = niy;
			}
		}

		void ring(float cx, float cy, float rx, float ry, float thickness, int argb, float phase) {
			arc(cx, cy, rx, ry, thickness, argb, phase, phase + TWO_PI);
		}

		/** A disc with a feathered rim, so its edge is not a cut-out circle. */
		void discSoft(float cx, float cy, float r, int argb) {
			if (r <= 0f) {
				return;
			}
			float feather = Math.max(1.2f, r * 0.045f);
			ring(cx, cy, r + feather, r + feather, feather, a(argb, 0.35f), 0f);
			ring(cx, cy, r + feather * 0.45f, r + feather * 0.45f, feather * 0.55f, a(argb, 0.70f), 0f);
			disc(cx, cy, r, r, argb);
		}

		void glow(float cx, float cy, float rx, float ry, int argb, int steps) {
			glow(cx, cy, rx, ry, argb, steps, 56);
		}

		/**
		 * A soft radial falloff, drawn as a handful of bands whose inner and outer
		 * edges carry different alphas.
		 *
		 * Stacking flat-alpha discs — the obvious way to fake a gradient — leaves
		 * the step between each pair of discs visible as a faint ring, and on a
		 * large glow those rings read as a target. Interpolating the alpha across
		 * each band instead means the colour is continuous everywhere and only its
		 * slope changes at a band edge, so what lands on screen is a gradient
		 * rather than a set of circles. {@code cap} limits how finely the rim is
		 * cut, for the wide, soft shapes that do not need many segments.
		 */
		void glow(float cx, float cy, float rx, float ry, int argb, int steps, int cap) {
			int baseAlpha = (argb >>> 24) & 0xFF;
			if (baseAlpha == 0 || steps <= 0 || rx <= 0f || ry <= 0f) {
				return;
			}
			int rgb = argb & 0xFFFFFF;
			int bands = Math.max(2, Math.min(7, steps / 2 + 1));
			int segments = Math.max(10, Math.min(cap, stepsFor(rx, ry, cap)));
			float previousRadius = 0f;
			int previousColor = (baseAlpha << 24) | rgb;
			for (int i = 1; i <= bands; i++) {
				float t = i / (float) bands;
				float falloff = (1f - t) * (1f - t);
				int color = (Math.round(baseAlpha * falloff) << 24) | rgb;
				band(cx, cy, rx * previousRadius, ry * previousRadius, rx * t, ry * t,
						previousColor, color, segments);
				previousRadius = t;
				previousColor = color;
			}
		}

		/**
		 * The band between two ellipses, filled with a gradient from the colour at
		 * its inner edge to the colour at its outer one. This is how a disc with a
		 * hole in it is drawn: an accretion disc, an iris, a ring of light.
		 */
		void annulusGradient(float cx, float cy, float innerRx, float innerRy,
				float outerRx, float outerRy, int innerColor, int outerColor, int bands) {
			int count = Math.max(1, bands);
			int segments = Math.max(12, Math.min(160, stepsFor(outerRx, outerRy, 160)));
			for (int i = 0; i < count; i++) {
				float t0 = i / (float) count;
				float t1 = (i + 1f) / count;
				int c0 = i == 0 ? innerColor : mix(innerColor, outerColor, t0);
				int c1 = mix(innerColor, outerColor, t1);
				band(cx, cy,
						innerRx + (outerRx - innerRx) * t0, innerRy + (outerRy - innerRy) * t0,
						innerRx + (outerRx - innerRx) * t1, innerRy + (outerRy - innerRy) * t1,
						c0, c1, segments);
			}
		}

		/**
		 * A turned slice of an annulus: bands from {@code r0} to {@code r1}, from
		 * one angle to another, flattened by {@code tiltY} to read as a disc seen
		 * at an angle. This is what lets the two halves of a black hole's disc be
		 * drawn either side of its shadow.
		 */
		void annulusArc(float cx, float cy, float r0, float r1, float tiltY, float from, float to,
				int innerColor, int outerColor, int bands) {
			float span = to - from;
			if (r1 <= r0 || r0 <= 0f || Math.abs(span) < 1.0e-4f) {
				return;
			}
			int count = Math.max(1, bands);
			int segments = Math.max(4, Math.min(120, Math.round(Math.abs(span) / 0.075f)));
			for (int b = 0; b < count; b++) {
				float t0 = b / (float) count;
				float t1 = (b + 1f) / count;
				float ra = r0 + (r1 - r0) * t0;
				float rb = r0 + (r1 - r0) * t1;
				int ca = b == 0 ? innerColor : mix(innerColor, outerColor, t0);
				int cb = mix(innerColor, outerColor, t1);
				for (int i = 0; i < segments; i++) {
					float f0 = from + span * i / segments;
					float f1 = from + span * (i + 1) / segments;
					float c0 = (float) Math.cos(f0);
					float s0 = (float) Math.sin(f0);
					float c1 = (float) Math.cos(f1);
					float s1 = (float) Math.sin(f1);
					vertex(cx + c0 * ra, cy + s0 * ra * tiltY, ca);
					vertex(cx + c0 * rb, cy + s0 * rb * tiltY, cb);
					vertex(cx + c1 * rb, cy + s1 * rb * tiltY, cb);
					vertex(cx + c1 * ra, cy + s1 * ra * tiltY, ca);
				}
			}
		}

		/** One annular band, with a colour on each of its two edges. */
		private void band(float cx, float cy, float innerX, float innerY, float outerX,
				float outerY, int innerColor, int outerColor, int segments) {
			float pix = cx + innerX;
			float piy = cy;
			float pox = cx + outerX;
			float poy = cy;
			for (int i = 1; i <= segments; i++) {
				double angle = i * Math.PI * 2.0 / segments;
				float nix = cx + (float) Math.cos(angle) * innerX;
				float niy = cy + (float) Math.sin(angle) * innerY;
				float nox = cx + (float) Math.cos(angle) * outerX;
				float noy = cy + (float) Math.sin(angle) * outerY;
				vertex(pix, piy, innerColor);
				vertex(pox, poy, outerColor);
				vertex(nox, noy, outerColor);
				vertex(nix, niy, innerColor);
				pix = nix;
				piy = niy;
				pox = nox;
				poy = noy;
			}
		}
	}

	// ---------------------------------------------------------- persistence

	@Override
	public void loadConfig(JsonObject json) {
		theme.select(ConfigValues.stringValue(json, "theme", theme.option()));
		speed.set(ConfigValues.intValue(json, "speed", 10));
		opacity.set(ConfigValues.intValue(json, "opacity", 100));
		stars.set(ConfigValues.boolValue(json, "stars", true));
		fog.set(ConfigValues.boolValue(json, "fog", false));
	}

	@Override
	public void saveConfig(JsonObject json) {
		json.addProperty("theme", theme.option());
		json.addProperty("speed", speed.get());
		json.addProperty("opacity", opacity.get());
		json.addProperty("stars", stars.value());
		json.addProperty("fog", fog.value());
	}
}
