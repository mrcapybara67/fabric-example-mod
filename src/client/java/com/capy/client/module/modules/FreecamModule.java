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
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Freecam — the camera leaves the body and flies on its own.
 *
 * <h2>What actually moves</h2>
 * Nothing but the camera. There is no second entity, no teleport and no packet:
 * {@code Camera.setup} is where vanilla decides where the view is for the
 * frame, and this module overwrites the position and the rotation at the end of
 * it. The level keeps streaming around the player, the body keeps standing
 * where it stood, and switching the module off restores vanilla's view on the
 * very next frame because nothing was ever changed to undo.
 *
 * <h2>Why it is quiet on a server</h2>
 * A server only knows what the client tells it: position, rotation and input.
 * This module tells it nothing new. The body does not move, so no position
 * changes and no movement packet is produced; the mouse never reaches
 * {@code LocalPlayer.turn}, so no look packet is produced either; and the
 * camera that the player sees is purely local state that is never transmitted.
 * From the server's side the player is standing still and looking straight
 * ahead, which is exactly what they are doing.
 *
 * <p>That is the whole of what a client can do. A camera is never described to
 * a server, so there is nothing to spoof — and equally, no client can promise
 * that a server will not draw its own conclusions from behaviour. Freecam is a
 * viewing tool: it cannot move the body, open containers, reach entities or act
 * at a distance, and anything that claims otherwise is not a camera.</p>
 *
 * <h2>The feel</h2>
 * Flight is velocity based, not position based. The keys ask for a velocity and
 * the camera eases towards it, so it accelerates out of a standstill and
 * glides to a stop instead of snapping between speeds — the easing is resolved
 * against the frame time, so it feels identical at 30 and at 300 frames per
 * second. Position is integrated from that velocity and interpolated to the
 * frame, so motion is continuous rather than stepping at twenty hertz.
 *
 * <h2>The three hooks</h2>
 * <ul>
 *   <li><b>where</b> — {@code FreecamCameraMixin}, at the tail of
 *       {@code Camera.setup};</li>
 *   <li><b>where it looks</b> — {@code FreecamMouseMixin}, at the head of
 *       {@code MouseHandler.handleAccumulatedMovement}, which spends the delta
 *       on the camera and swallows it so the body never turns;</li>
 *   <li><b>what the body does</b> — {@code FreecamInputMixin}, at the head of
 *       {@code LocalPlayer.applyInput}, so the same keys cannot walk the
 *       body away underneath the camera.</li>
 * </ul>
 *
 * <h2>When it switches itself off</h2>
 * A detached camera outlives its usefulness the moment the player is no longer
 * standing in the world: a respawn, a death, a bed or a level change would
 * otherwise leave the view parked over the old position. Those states turn the
 * module off rather than drawing something wrong, and a new world re-primes the
 * camera at the player's eye instead of at coordinates from the old one.
 */
public class FreecamModule extends Module implements Tickable, Configurable {
	/** Just short of straight up and down: a camera that flips over is useless. */
	private static final float MIN_PITCH = -89.9f;
	private static final float MAX_PITCH = 89.9f;
	/** One tick in seconds: velocity is in blocks per second, so this converts. */
	private static final double TICK_SECONDS = 1.0 / 20.0;
	/** How much faster the flight is while the sprint key is held. */
	private static final double SPRINT_FACTOR = 2.5;
	/** Velocity kept after the distance guard pulls the camera back in. */
	private static final double CLAMP_DAMPING = 0.15;

	private final SliderSetting horizontalSpeed =
			setting(new SliderSetting("horizontal_speed", 16, 1, 64, 0));
	private final SliderSetting verticalSpeed =
			setting(new SliderSetting("vertical_speed", 12, 1, 64, 0));
	private final SliderSetting lookSpeed =
			setting(new SliderSetting("look_speed", 100, 10, 300));
	/** E-folds per second: how hard the camera pushes towards the speed asked for. */
	private final SliderSetting acceleration =
			setting(new SliderSetting("acceleration", 14, 2, 30));
	/**
	 * How far the camera may stray from the body, in blocks; 0 is unlimited.
	 *
	 * <p>Not a restriction for its own sake. A camera outside the loaded chunks
	 * is looking at terrain the client does not have, so it renders the edge of
	 * the world — and the further it goes, the more of the view is nothing.
	 * Staying near the body keeps the picture complete on any server, whatever
	 * its view distance, and keeps the camera inside the area the player can
	 * legitimately see.</p>
	 */
	private final SliderSetting maxDistance =
			setting(new SliderSetting("max_distance", 96, 0, 256, 0));
	private final BooleanSetting freezePlayer = setting(new BooleanSetting("freeze_player", true));
	private final BooleanSetting sprintBoost = setting(new BooleanSetting("sprint_boost", true));

	// Velocity in blocks per second, and the resulting position. The previous
	// tick's position is kept so a frame can interpolate between the two.
	private double vx;
	private double vy;
	private double vz;
	private double x;
	private double y;
	private double z;
	private double prevX;
	private double prevY;
	private double prevZ;
	/** Rotation is frame-live: the mouse writes it, so interpolation would lag. */
	private float yaw;
	private float pitch;
	/** False until the camera has been placed at the player's eye. */
	private boolean primed;
	/** The level the camera was primed in, so a new world re-primes. */
	private ClientLevel lastLevel;

	public FreecamModule() {
		super(Category.DONUT_SMP, "freecam");
	}

	/**
	 * True while the camera should be drawn from this module.
	 *
	 * <p>Requires a primed position as well as the toggle: a module restored
	 * from the config before a world exists must not park the camera at 0,0,0
	 * once one does.</p>
	 */
	public boolean isActive() {
		return isEnabled() && primed;
	}

	/** True while the body should ignore its own movement input. */
	public boolean shouldFreezePlayer() {
		return isActive() && freezePlayer.value();
	}

	@Override
	protected void onToggle(boolean state) {
		primed = false;
		lastLevel = null;
		stop();
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!isEnabled() || mc == null || mc.player == null || mc.level == null) {
			primed = false;
			return;
		}
		LocalPlayer player = mc.player;
		// States where a detached camera is worse than no freecam: the player
		// is not standing in the world any more, so the view would be left over
		// a body that has moved on (or died).
		if (player.isDeadOrDying() || player.isSleeping() || player.isRemoved()) {
			setEnabled(false);
			return;
		}
		if (mc.level != lastLevel) {
			// A new world (or the first one): start at the player's eye rather
			// than at coordinates that belong to the previous world.
			lastLevel = mc.level;
			prime(player);
			return;
		}
		if (!primed) {
			prime(player);
			return;
		}
		prevX = x;
		prevY = y;
		prevZ = z;
		fly(mc, player);
	}

	/** Places the camera at the player's eye, looking where they look. */
	private void prime(LocalPlayer player) {
		Vec3 eye = player.getEyePosition();
		x = prevX = eye.x;
		y = prevY = eye.y;
		z = prevZ = eye.z;
		yaw = player.getYRot();
		pitch = player.getXRot();
		stop();
		primed = true;
	}

	private void stop() {
		vx = 0.0;
		vy = 0.0;
		vz = 0.0;
	}

	/**
	 * One tick of flight.
	 *
	 * <p>The keys are read straight from the vanilla mappings, so a rebound key
	 * works without this module knowing anything about keybinds. Diagonal
	 * movement is normalised, or holding two keys would fly 1.41 times as fast
	 * as holding one — the classic freecam speed bug.</p>
	 */
	private void fly(Minecraft mc, LocalPlayer player) {
		double speedH = horizontalSpeed.real();
		double speedV = verticalSpeed.real();
		boolean boosting = sprintBoost.value() && mc.options != null && mc.options.keySprint.isDown();
		if (boosting) {
			speedH *= SPRINT_FACTOR;
			speedV *= SPRINT_FACTOR;
		}

		boolean up = mc.options != null && mc.options.keyUp.isDown();
		boolean down = mc.options != null && mc.options.keyDown.isDown();
		boolean right = mc.options != null && mc.options.keyRight.isDown();
		boolean left = mc.options != null && mc.options.keyLeft.isDown();
		boolean jump = mc.options != null && mc.options.keyJump.isDown();
		boolean sneak = mc.options != null && mc.options.keyShift.isDown();

		double forward = axis(up, down);
		double strafe = axis(right, left);
		double lift = axis(jump, sneak);
		double length = Math.sqrt(forward * forward + strafe * strafe);
		if (length > 1.0) {
			forward /= length;
			strafe /= length;
		}

		double radians = Math.toRadians(yaw);
		double sin = Math.sin(radians);
		double cos = Math.cos(radians);
		// Yaw 0 faces +Z, so forward is (-sin, cos) and right is (cos, sin).
		double wantX = (-sin * forward + cos * strafe) * speedH;
		double wantZ = (cos * forward + sin * strafe) * speedH;
		double wantY = lift * speedV;

		// Easing towards the wanted velocity rather than setting it: the camera
		// leans into a movement and coasts out of it, and because the easing is
		// resolved against the tick length the feel does not change with fps.
		float ease = (float) Math.max(1.0, acceleration.real());
		vx = Motion.approach((float) vx, (float) wantX, ease, (float) TICK_SECONDS);
		vy = Motion.approach((float) vy, (float) wantY, ease, (float) TICK_SECONDS);
		vz = Motion.approach((float) vz, (float) wantZ, ease, (float) TICK_SECONDS);

		x += vx * TICK_SECONDS;
		y += vy * TICK_SECONDS;
		z += vz * TICK_SECONDS;
		keepNearBody(player);
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
	 * delta vanilla would have used, so the sensitivity and the feel are the
	 * ones the player already has — scaled by the look speed setting. The yaw is
	 * kept in -180..180 so a long session cannot accumulate it into a value the
	 * camera math has to cope with.</p>
	 */
	public void applyLook(double deltaX, double deltaY) {
		if (deltaX == 0.0 && deltaY == 0.0) {
			return;
		}
		float factor = (float) lookSpeed.real() / 100f;
		yaw += (float) (deltaX * factor);
		yaw = wrapDegrees(yaw);
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

	/** The camera position for a frame, interpolated between the last two ticks. */
	public Vec3 cameraPosition(float partialTick) {
		float t = Math.max(0f, Math.min(1f, partialTick));
		return new Vec3(prevX + (x - prevX) * t, prevY + (y - prevY) * t, prevZ + (z - prevZ) * t);
	}

	public float cameraYaw() {
		return yaw;
	}

	public float cameraPitch() {
		return pitch;
	}

	/** True when the player is looking through the detached camera. */
	public boolean isFlying() {
		return isActive();
	}

	// ---------------------------------------------------------- persistence

	@Override
	public void loadConfig(JsonObject json) {
		horizontalSpeed.set(ConfigValues.intValue(json, "horizontal_speed", 16));
		verticalSpeed.set(ConfigValues.intValue(json, "vertical_speed", 12));
		lookSpeed.set(ConfigValues.intValue(json, "look_speed", 100));
		acceleration.set(ConfigValues.intValue(json, "acceleration", 14));
		maxDistance.set(ConfigValues.intValue(json, "max_distance", 96));
		freezePlayer.set(ConfigValues.boolValue(json, "freeze_player", true));
		sprintBoost.set(ConfigValues.boolValue(json, "sprint_boost", true));
	}

	@Override
	public void saveConfig(JsonObject json) {
		json.addProperty("horizontal_speed", horizontalSpeed.get());
		json.addProperty("vertical_speed", verticalSpeed.get());
		json.addProperty("look_speed", lookSpeed.get());
		json.addProperty("acceleration", acceleration.get());
		json.addProperty("max_distance", maxDistance.get());
		json.addProperty("freeze_player", freezePlayer.value());
		json.addProperty("sprint_boost", sprintBoost.value());
	}
}
