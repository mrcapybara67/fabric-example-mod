package com.capy.client.module.modules;

import com.capy.client.config.ConfigValues;
import com.capy.client.module.Category;
import com.capy.client.module.Configurable;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.Module;
import com.capy.client.module.SliderSetting;
import com.capy.client.module.modules.movement.InertiaRamp;
import com.capy.client.module.modules.movement.MovementKickGuard;
import com.capy.client.module.modules.movement.MovementProfile;
import com.capy.client.module.modules.movement.MovementProfiles;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

/**
 * Fly — moves the body through the air in survival, at a speed the player sets.
 *
 * <h2>Where the movement happens</h2>
 * The block of vanilla that would normally solve gravity, drag and collisions
 * for the player is {@code LivingEntity.travel}. This module replaces that one
 * call for the local player only, and does the movement itself: it reads the
 * ordinary movement keys, builds one velocity for the tick and hands it to
 * {@code Entity.move} with {@code MoverType.SELF}, which keeps the player's
 * box colliding with the world. Nothing else in vanilla's tick is skipped, so
 * food, effects, riding, block interaction and the camera all keep working.
 *
 * <h2>Why the body does not fall</h2>
 * Vanilla's travel is where gravity is added. Since it is not run at all while
 * the module is on, there is no gravity to cancel and no accumulated vertical
 * velocity to fight; no input means the player hovers exactly where they are.
 * Releasing the keys is therefore a stop, not a fall.
 *
 * <h2>What the server is told</h2>	 * The server's "flying too long" check reads the movement packet's
	 * {@code onGround} flag. This module deliberately does not force that flag:
	 * claiming to stand while moving vertically caused contradictory movement
	 * packets. The speed sliders stay under the configured per-server profile,
	 * which keeps position deltas small. No packet is invented and nothing is
	 * sent outside vanilla's own movement path.
 */
public class FlyModule extends Module implements Configurable {
	/** Ticks per second; the sliders are in blocks per second. */
	private static final double TICKS_PER_SECOND = 20.0;

	private final SliderSetting horizontalSpeed =
			setting(new SliderSetting("horizontal_speed", 8, 40, 400, 1));
	private final SliderSetting verticalSpeed =
			setting(new SliderSetting("vertical_speed", 6, 20, 300, 1));
	/** Caps the sliders at the current server's calibrated profile. */
	private final BooleanSetting serverSafe = setting(new BooleanSetting("server_safe", true));
	/** The old instant movement, kept for servers known to have no checks. */
	private final BooleanSetting legacyMode = setting(new BooleanSetting("legacy_mode", false));
	private final InertiaRamp ramp = new InertiaRamp();

	public FlyModule() {
		super(Category.WORLD, "fly");
	}

	/**
	 * The module, if it should take over the given player's travel this tick.
	 * Package visible because the mixin is the only caller.
	 */
	public static FlyModule active() {
		FlyModule module = com.capy.client.module.ModuleManager.get().get(FlyModule.class);
		return module != null && module.isEnabled() ? module : null;
	}

	/** True when vanilla's travel must be replaced for this player. */
	public boolean overridesTravel(LocalPlayer player) {
		if (!isEnabled()) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.player != player || mc.level == null) {
			return false;
		}
		// A passenger is moved by the vehicle, a glider by the elytra physics,
		// and a dead or sleeping player by vanilla's own state machine. Each
		// of those is a different movement path, and taking it over here would
		// fight the code that already owns it.
		return !player.isPassenger() && !player.isFallFlying() && !player.isDeadOrDying()
				&& !player.isSleeping() && !player.isRemoved();
	}

	/** One tick of flight, replacing vanilla's travel. */
	public void fly(LocalPlayer player) {
		Minecraft mc = Minecraft.getInstance();
		MovementProfile profile = MovementProfiles.get().profile(MovementKickGuardHost.host());
		double horizontal = horizontalSpeed.real() / TICKS_PER_SECOND;
		double vertical = verticalSpeed.real() / TICKS_PER_SECOND;
		if (serverSafe.value()) {
			horizontal = Math.min(horizontal, profile.horizontal() / TICKS_PER_SECOND);
			vertical = Math.min(vertical, profile.vertical() / TICKS_PER_SECOND);
		}

		double forward = 0.0;
		double strafe = 0.0;
		double lift = 0.0;
		// A screen is open: the movement keys are not the player's intent and
		// the world still ticks behind the GUI. Holding position is the only
		// honest answer there - returning to vanilla would drop them.
		if (mc != null && mc.screen == null && player.input != null) {
			Input keys = player.input.keyPresses;
			if (keys != null) {
				forward = (keys.forward() ? 1 : 0) - (keys.backward() ? 1 : 0);
				strafe = (keys.right() ? 1 : 0) - (keys.left() ? 1 : 0);
				lift = (keys.jump() ? 1 : 0) - (keys.shift() ? 1 : 0);
			}
		}

		double length = Math.sqrt(forward * forward + strafe * strafe);
		if (length > 1.0) {
			forward /= length;
			strafe /= length;
		}

		// Yaw 0 faces +Z, so forward is (-sin, cos) and the right hand of that
		// facing is (-cos, -sin). Movement is horizontal-only by design: the
		// vertical axis belongs to the jump and sneak keys so that looking up
		// or down never changes the altitude.
		double radians = Math.toRadians(player.getYRot());
		double sin = Math.sin(radians);
		double cos = Math.cos(radians);
		Vec3 motion = new Vec3(
				(-sin * forward - cos * strafe) * horizontal,
				lift * vertical,
				(cos * forward - sin * strafe) * horizontal);

		// Ramp into the target instead of snapping to it. The first tick is a
		// fraction of the requested speed, so the position delta grows the way
		// a player's own acceleration does. Releasing the keys decays it the
		// same way rather than dropping straight to zero.
		ramp.step(motion.x, motion.y, motion.z, profile.inertia(), legacyMode.value());
		Vec3 velocity = new Vec3(ramp.x(), ramp.y(), ramp.z());
		MovementKickGuard.noteActive();
		// The move is resolved against the world, so walls still stop the
		// player: this is flight, not noclip.
		player.setDeltaMovement(velocity);
		player.move(MoverType.SELF, velocity);
		player.setDeltaMovement(Vec3.ZERO);
		// Do not claim to be standing while moving through the air. The old
		// forced true flag contradicted every vertical movement packet.
		player.resetFallDistance();
	}

	// ---------------------------------------------------------- persistence

	@Override
	protected void onToggle(boolean state) {
		ramp.reset();
	}

	@Override
	public void loadConfig(JsonObject json) {
		horizontalSpeed.set(ConfigValues.intValue(json, "horizontal_speed", 8));
		verticalSpeed.set(ConfigValues.intValue(json, "vertical_speed", 6));
		serverSafe.set(ConfigValues.boolValue(json, "server_safe", true));
		legacyMode.set(ConfigValues.boolValue(json, "legacy_mode", false));
		if (json != null && json.has("movement_profiles") && json.get("movement_profiles").isJsonObject()) {
			MovementProfiles.get().load(json.getAsJsonObject("movement_profiles"));
		}
		ramp.reset();
	}

	@Override
	public void saveConfig(JsonObject json) {
		json.addProperty("horizontal_speed", horizontalSpeed.get());
		json.addProperty("vertical_speed", verticalSpeed.get());
		json.addProperty("server_safe", serverSafe.value());
		json.addProperty("legacy_mode", legacyMode.value());
		json.add("movement_profiles", MovementProfiles.get().toJson());
	}
}
