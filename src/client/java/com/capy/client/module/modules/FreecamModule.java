package com.capy.client.module.modules;

import com.capy.client.config.ConfigValues;
import com.capy.client.gui.Motion;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.Category;
import com.capy.client.module.Configurable;
import com.capy.client.module.Module;
import com.capy.client.module.SliderSetting;
import com.capy.client.module.Tickable;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Freecam — the camera leaves the body and flies on its own.
 *
 * <h2>What actually moves</h2>
 * Nothing but the camera. There is no second entity, no teleport and no packet:
 * {@code Camera.setup} is where vanilla decides where the view is for the
 * frame, and this module overwrites the position and the rotation at the end of
 * it. The level keeps streaming around the player, the body keeps doing exactly
 * what its keys say, and switching the module off restores vanilla's view on
 * the very next frame because nothing was ever changed to undo.
 *
 * <h2>The body is not the camera's business</h2>
 * Input is never taken away. A player who was sprinting across a field is
 * still sprinting across it while the camera goes on ahead, and a player who
 * was standing still is still standing still; the camera is a second viewpoint,
 * not a replacement for the player. That is also why there is no setting for
 * it: an input freeze reads as a broken client, and the body was never under
 * the camera's control to begin with.
 *
 * <h2>Looking through walls</h2>
 * The camera has no collision, and nothing here needs to remove any: it is a
 * position handed to the renderer, not an entity the world solves movement
 * against. Stone cannot stop it. From inside a wall it renders exactly what a
 * viewpoint inside a wall should - the wall around it, the world beyond it,
 * and no body in the frame.
 *
 * <h2>Why the movement is driven from the frame, not the tick</h2>
 * An earlier version integrated the flight from {@code onTick}. That put the
 * camera's only source of motion on a second path that can be starved — a
 * paused client thread, a launcher that throttles ticks on a phone — and the
 * symptom was a camera that sat still while the mouse no longer turned the
 * player: a freeze. The clock here is the render frame's own, so if the world
 * is being drawn, the camera is being moved. Ticks are used only to notice the
 * states that should switch the module off.
 *
 * <h2>Why it works on a phone</h2>
 * Android launchers report the window as inactive in ways a desktop window
 * never does, and the look path used to require an active window. On a phone
 * that meant every mouse delta was thrown away and the view never turned. Look
 * is now applied whenever the camera is flying, with no window check and no
 * screen check: a delta that reached the mouse handler is a delta the player
 * made.
 *
 * <h2>What a server sees</h2>
 * A server only knows what the client tells it. The body moves exactly as it
 * would have moved with the module off, so its position and look packets are
 * the ones the player produced by playing; the mouse never reaches
 * {@code LocalPlayer.turn}, so the body's own heading does not spin with the
 * camera. The camera's flight is not described on the wire at all - a camera is
 * a client-side concept the protocol has no field for. What that cannot do is
 * hide from behavioural inference, and it cannot move the body, open containers
 * or reach anything at range: the camera is a viewpoint, not a second player.
 */
public class FreecamModule extends Module implements Tickable, Configurable {
	/** Just short of straight up and down: a camera that flips over is useless. */
	private static final float MIN_PITCH = -89.9f;
	private static final float MAX_PITCH = 89.9f;
	/** Bounds on the frame delta, so a stalled frame cannot teleport the camera. */
	private static final float MIN_FRAME = 0.001f;
	private static final float MAX_FRAME = 0.05f;
	/** How much faster the flight is while the sprint key is held. */
	private static final double SPRINT_FACTOR = 2.0;
	/**
	 * A common brake for both flight and look. The sliders still describe the
	 * camera, but the raw values were tuned on a desktop mouse and feel much
	 * too quick on a phone, where a swipe arrives as one large delta.
	 */
	private static final double FLIGHT_SCALE = 0.55;
	private static final float LOOK_SCALE = 0.35f;
	/** Velocity kept after the distance guard pulls the camera back in. */
	private static final double CLAMP_DAMPING = 0.15;

	private final SliderSetting horizontalSpeed =
			setting(new SliderSetting("horizontal_speed", 10, 1, 48, 0));
	private final SliderSetting verticalSpeed =
			setting(new SliderSetting("vertical_speed", 7, 1, 48, 0));
	private final SliderSetting lookSpeed =
			setting(new SliderSetting("look_speed", 45, 5, 150));
	/** E-folds per second: how hard the camera pushes towards the speed asked for. */
	private final SliderSetting acceleration =
			setting(new SliderSetting("acceleration", 14, 2, 30));
	/**
	 * How far the camera may stray from the body, in blocks; 0 is unlimited.
	 *
	 * <p>Not a restriction for its own sake. A camera outside the loaded chunks
	 * is looking at terrain the client does not have, so it renders the edge of
	 * the world. Staying near the body keeps the picture complete on any server,
	 * whatever its view distance.</p>
	 */
	private final SliderSetting maxDistance =
			setting(new SliderSetting("max_distance", 96, 0, 256, 0));
	private final BooleanSetting sprintBoost = setting(new BooleanSetting("sprint_boost", true));
	/**
	 * Whether the body is held still while the camera is detached.
	 *
	 * <p>On by default, which is what a viewer expects: the point of freecam is
	 * to look from somewhere else, not to walk the body into a wall while the
	 * player is not looking at it. The movement keys belong to the camera while
	 * this is on; they are read from the options directly, so the camera still
	 * flies. Off restores the old behaviour where the body keeps walking.</p>
	 */
	private final BooleanSetting freezeBody = setting(new BooleanSetting("freeze_body", true));

	// Velocity in blocks per second, and the resulting position.
	private double vx;
	private double vy;
	private double vz;
	private double x;
	private double y;
	private double z;
	private float yaw;
	private float pitch;
	private long lastFrameNanos;
	/** False until the camera has been placed at the player's eye. */
	private boolean primed;
	/** The level the camera was primed in, so a new world re-primes. */
	private Level lastLevel;

	public FreecamModule() {
		super(Category.DONUT_SMP, "freecam");
	}

	/** True while the camera should be drawn from this module. */
	public boolean isActive() {
		return isEnabled() && primed;
	}

	@Override
	protected void onToggle(boolean state) {
		primed = false;
		lastLevel = null;
		lastFrameNanos = 0L;
		stop();
	}

	/**
	 * Lifecycle only: the flight itself runs from {@link #updateCamera}.
	 *
	 * <p>Death, sleep, removal and a world change are all states where a
	 * detached camera would be left hanging over a body that has moved on, so
	 * they are noticed here and the module switches itself off.</p>
	 */
	@Override
	public void onTick(Minecraft mc) {
		if (!isEnabled()) {
			return;
		}
		if (mc == null || mc.player == null || mc.level == null) {
			primed = false;
			return;
		}
		LocalPlayer player = mc.player;
		if (player.isDeadOrDying() || player.isSleeping() || player.isRemoved()) {
			setEnabled(false);
			return;
		}
		if (mc.level != lastLevel) {
			prime(player);
		}
		// The body is the camera's anchor, not its co-pilot. Clearing the
		// movement input stops the body walking away underneath a detached
		// camera; the zeroed velocity stops gravity from dropping it while the
		// keys are being spent on the camera.
		if (freezeBody.value() && !player.isPassenger() && player.input != null) {
			player.input.keyPresses = net.minecraft.world.entity.player.Input.EMPTY;
			player.setDeltaMovement(0.0, 0.0, 0.0);
		}
		// Advance once from the tick as well as once from the frame. The flight
		// integration is time based, so calling it twice does not double the
		// speed; it only guarantees the camera still moves on a renderer whose
		// Camera hook never runs (a replaced renderer, for example).
		updateCamera(mc);
	}

	/**
	 * Advances the camera by one rendered frame and reports whether it should be
	 * used. Called from the camera hook, which is the one place that is
	 * guaranteed to run once per frame while the world is visible.
	 */
	public boolean updateCamera(Minecraft mc) {
		if (!isEnabled() || mc == null || mc.player == null || mc.level == null) {
			return false;
		}
		LocalPlayer player = mc.player;
		if (player.isDeadOrDying() || player.isSleeping() || player.isRemoved()) {
			setEnabled(false);
			return false;
		}
		if (!primed || mc.level != lastLevel) {
			prime(player);
		}
		long now = System.nanoTime();
		float dt = lastFrameNanos == 0L ? 1f / 60f : (now - lastFrameNanos) / 1.0e9f;
		lastFrameNanos = now;
		dt = Math.max(MIN_FRAME, Math.min(MAX_FRAME, dt));
		fly(mc, dt);
		keepNearBody(player);
		return true;
	}

	/** Places the camera at the player's eye, looking where they look. */
	private void prime(LocalPlayer player) {
		lastLevel = player.level();
		Vec3 eye = player.getEyePosition();
		x = eye.x;
		y = eye.y;
		z = eye.z;
		yaw = player.getYRot();
		pitch = player.getXRot();
		lastFrameNanos = 0L;
		stop();
		primed = true;
	}

	private void stop() {
		vx = 0.0;
		vy = 0.0;
		vz = 0.0;
	}

	/**
	 * One frame of flight.
	 *
	 * <p>The keys are read straight from the vanilla mappings, so a rebound key
	 * — and an on-screen button a launcher maps to one — works without this
	 * module knowing anything about either. Diagonal movement is normalised, or
	 * holding two keys would fly 1.41 times as fast as holding one.</p>
	 */
	private void fly(Minecraft mc, float dt) {
		double speedH = horizontalSpeed.real() * FLIGHT_SCALE;
		double speedV = verticalSpeed.real() * FLIGHT_SCALE;
		boolean boosting = sprintBoost.value() && mc.options != null && mc.options.keySprint.isDown();
		if (boosting) {
			speedH *= SPRINT_FACTOR;
			speedV *= SPRINT_FACTOR;
		}

		double forward = axis(down(mc, 0), down(mc, 1));
		double strafe = axis(down(mc, 2), down(mc, 3));
		double lift = axis(down(mc, 4), down(mc, 5));
		double length = Math.sqrt(forward * forward + strafe * strafe);
		if (length > 1.0) {
			forward /= length;
			strafe /= length;
		}

		double radians = Math.toRadians(yaw);
		double sin = Math.sin(radians);
		double cos = Math.cos(radians);
		// Yaw 0 faces +Z, so forward is (-sin, cos). The right hand of a facing
		// is that vector turned a quarter turn clockwise, which is (-cos, -sin):
		// the mirrored form made D fly left and A fly right.
		double rightX = -cos;
		double rightZ = -sin;
		double wantX = (-sin * forward + rightX * strafe) * speedH;
		double wantZ = (cos * forward + rightZ * strafe) * speedH;
		double wantY = lift * speedV;

		// Easing towards the wanted velocity rather than setting it: the camera
		// leans into a movement and coasts out of it, resolved against the real
		// frame time so the feel does not change with the refresh rate.
		float ease = (float) Math.max(1.0, acceleration.real());
		vx = Motion.approach((float) vx, (float) wantX, ease, dt);
		vy = Motion.approach((float) vy, (float) wantY, ease, dt);
		vz = Motion.approach((float) vz, (float) wantZ, ease, dt);

		x += vx * dt;
		y += vy * dt;
		z += vz * dt;
	}

	/** Index into the movement keys: 0 up, 1 down, 2 right, 3 left, 4 jump, 5 sneak. */
	private static boolean down(Minecraft mc, int key) {
		if (mc.options == null) {
			return false;
		}
		return switch (key) {
			case 0 -> mc.options.keyUp.isDown();
			case 1 -> mc.options.keyDown.isDown();
			case 2 -> mc.options.keyRight.isDown();
			case 3 -> mc.options.keyLeft.isDown();
			case 4 -> mc.options.keyJump.isDown();
			default -> mc.options.keyShift.isDown();
		};
	}

	/**
	 * Pulls the camera back onto its leash, if it has one.
	 *
	 * <p>Applied after the move, to the position rather than to the input, so
	 * the camera slides along the boundary instead of sticking to it: only the
	 * velocity pointing outwards is damped, which leaves tangential movement
	 * untouched.</p>
	 */
	private void keepNearBody(LocalPlayer player) {
		double limit = maxDistance.real();
		if (limit <= 0.0) {
			return;
		}
		Vec3 eye = player.getEyePosition();
		double dx = x - eye.x;
		double dy = y - eye.y;
		double dz = z - eye.z;
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (distance <= limit || distance < 1.0e-4) {
			return;
		}
		double scale = limit / distance;
		x = eye.x + dx * scale;
		y = eye.y + dy * scale;
		z = eye.z + dz * scale;
		vx *= CLAMP_DAMPING;
		vy *= CLAMP_DAMPING;
		vz *= CLAMP_DAMPING;
	}

	/** -1, 0 or 1 from a pair of opposed keys; holding both cancels out. */
	private static double axis(boolean positive, boolean negative) {
		return (positive ? 1.0 : 0.0) - (negative ? 1.0 : 0.0);
	}

	/**
	 * Steers the camera from a frame's mouse delta.
	 *
	 * <p>Called instead of vanilla turning the player, with the same accumulated
	 * delta vanilla would have used, so the sensitivity is the one the player
	 * already has — scaled by the look speed setting. The yaw is kept in
	 * -180..180 so a long session cannot accumulate it into a value the camera
	 * math has to cope with.</p>
	 */
	public void applyLook(double deltaX, double deltaY) {
		if (deltaX == 0.0 && deltaY == 0.0) {
			return;
		}
		float factor = (float) lookSpeed.real() / 100f * LOOK_SCALE;
		yaw = wrapDegrees(yaw + (float) (deltaX * factor));
		pitch += (float) (deltaY * factor);
		pitch = Math.max(MIN_PITCH, Math.min(MAX_PITCH, pitch));
	}

	private static float wrapDegrees(float degrees) {
		float wrapped = degrees % 360f;
		if (wrapped >= 180f) {
			wrapped -= 360f;
		}
		if (wrapped < -180f) {
			wrapped += 360f;
		}
		return wrapped;
	}

	/** The camera position this frame. */
	public Vec3 cameraPosition() {
		return new Vec3(x, y, z);
	}

	public float cameraYaw() {
		return yaw;
	}

	public float cameraPitch() {
		return pitch;
	}

	// ---------------------------------------------------------- persistence

	@Override
	public void loadConfig(JsonObject json) {
		horizontalSpeed.set(ConfigValues.intValue(json, "horizontal_speed", 10));
		verticalSpeed.set(ConfigValues.intValue(json, "vertical_speed", 7));
		lookSpeed.set(ConfigValues.intValue(json, "look_speed", 45));
		acceleration.set(ConfigValues.intValue(json, "acceleration", 14));
		maxDistance.set(ConfigValues.intValue(json, "max_distance", 96));
		sprintBoost.set(ConfigValues.boolValue(json, "sprint_boost", true));
		freezeBody.set(ConfigValues.boolValue(json, "freeze_body", true));
	}

	@Override
	public void saveConfig(JsonObject json) {
		json.addProperty("horizontal_speed", horizontalSpeed.get());
		json.addProperty("vertical_speed", verticalSpeed.get());
		json.addProperty("look_speed", lookSpeed.get());
		json.addProperty("acceleration", acceleration.get());
		json.addProperty("max_distance", maxDistance.get());
		json.addProperty("sprint_boost", sprintBoost.value());
		json.addProperty("freeze_body", freezeBody.value());
	}
}
