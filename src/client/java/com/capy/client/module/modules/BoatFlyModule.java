package com.capy.client.module.modules;

import com.capy.client.config.ConfigValues;
import com.capy.client.module.Category;
import com.capy.client.module.Configurable;
import com.capy.client.module.Module;
import com.capy.client.module.ModuleManager;
import com.capy.client.module.SliderSetting;
import com.capy.client.module.Tickable;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;

/**
 * Boat Fly — the same flight as {@link FlyModule}, but the body stays in the
 * boat and the boat is what flies.
 *
 * <h2>Who owns the boat</h2>
 * A boat's movement is client driven: the client integrates the boat, then
 * sends {@code ServerboundMoveVehiclePacket} with its position. Vanilla's boat
 * tick also turns the paddle input into its own small velocity and rotation,
 * which is exactly the part that would fight a speed setting. That input is
 * blocked at {@code AbstractBoat.setInput} while this module controls the
 * boat, so the boating physics still runs - the boat still ticks, carries its
 * passenger and sends its packets - but it no longer steers itself.
 *
 * <h2>The flight</h2>
 * One velocity per tick is written to the boat at the end of the client tick.
 * Gravity is added back to the vertical component so that a released key
 * hovers instead of sinking, and the boat is turned to the player's yaw so the
 * camera steers it. Collision is left to vanilla, so the boat stops at walls;
 * {@link BoatNoclipModule} is the variant that removes that collision.
 *
 * <h2>What the server is told</h2>
 * Vanilla's vehicle floating check also reads the vehicle packet's ground
 * flag, and the packet is built from the boat's own flag at whatever point in
 * the tick it happens to be sent. Setting the flag on the boat is therefore
 * not enough; {@code BoatPacketMixin} sets it on the packet itself, which
 * makes the check independent of entity tick order. Speeds stay under the
 * server's per packet distance limit, so the position updates are accepted
 * rather than pulled back.
 */
public class BoatFlyModule extends Module implements Tickable, Configurable {
	/** Ticks per second; the sliders are in blocks per second. */
	private static final double TICKS_PER_SECOND = 20.0;

	private final SliderSetting horizontalSpeed =
			setting(new SliderSetting("horizontal_speed", 100, 40, 400, 1));
	private final SliderSetting verticalSpeed =
			setting(new SliderSetting("vertical_speed", 80, 20, 300, 1));
	/**
	 * The speed the hull sinks on its own when no vertical key is held, in
	 * blocks per second. The range starts at a very slow drift (0.10) and ends
	 * at a deliberate drop (10.00); the value is stored in hundredths so the
	 * slider can express both ends.
	 */
	private final SliderSetting boatFall =
			setting(new SliderSetting("boat_fall", 100, 10, 1000, 2));

	/** True for the noclip variant, which also disables boat collision. */
	private final boolean noclip;
	/** The boat this module last controlled, so its state can be restored. */
	private AbstractBoat controlled;
	/** Tracked position for the noclip variant, which steps past collision. */
	private double noclipX;
	private double noclipY;
	private double noclipZ;
	private boolean noclipPrimed;

	public BoatFlyModule() {
		this("boat_fly", false);
	}

	protected BoatFlyModule(String id, boolean noclip) {
		super(Category.WORLD, id);
		this.noclip = noclip;
	}

	/**
	 * The enabled boat flight module that owns the given vehicle, or null.
	 *
	 * <p>Both this module and {@link BoatNoclipModule} are found here; the
	 * input and packet mixins do not care which one is on, only that one of
	 * them is driving this boat.</p>
	 */
	public static BoatFlyModule activeFor(Entity vehicle) {
		if (!(vehicle instanceof AbstractBoat)) {
			return null;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.player == null || mc.player.getVehicle() != vehicle) {
			return null;
		}
		for (Module module : ModuleManager.get().modules()) {
			if (module instanceof BoatFlyModule boat && boat.isEnabled()) {
				return boat;
			}
		}
		return null;
	}

	@Override
	public void onTick(Minecraft mc) {
		if (!isEnabled()) {
			return;
		}
		if (mc == null || mc.player == null || mc.level == null) {
			release();
			return;
		}
		LocalPlayer player = mc.player;
		if (!(player.getVehicle() instanceof AbstractBoat boat)
				|| boat.getControllingPassenger() != player) {
			release();
			return;
		}
		if (boat != controlled) {
			release();
			controlled = boat;
		}

		double horizontal = horizontalSpeed.real() / TICKS_PER_SECOND;
		double vertical = verticalSpeed.real() / TICKS_PER_SECOND;

		double forward = 0.0;
		double strafe = 0.0;
		double lift = 0.0;
		if (mc.screen == null && player.input != null) {
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

		double radians = Math.toRadians(player.getYRot());
		double sin = Math.sin(radians);
		double cos = Math.cos(radians);
		double vx = (-sin * forward - cos * strafe) * horizontal;
		double vz = (cos * forward - sin * strafe) * horizontal;
		double vy;
		if (lift > 0) {
			vy = vertical;
		} else if (lift < 0) {
			vy = -vertical;
		} else {
			// Boat fall: with no vertical key held the hull descends on its
			// own. The slider is blocks per second, so 0.10 is a slow sink and
			// 10.00 is a drop.
			vy = -boatFall.real() / TICKS_PER_SECOND;
		}

		if (noclip) {
			// Direct stepping. Vanilla's collision solver is bypassed entirely,
			// so a wall cannot refuse the hull: the tracked position is advanced
			// by the requested step and written to the boat. The boat's own tick
			// still runs for passengers and packets, but its movement is
			// discarded here.
			if (!noclipPrimed) {
				noclipX = boat.getX();
				noclipY = boat.getY();
				noclipZ = boat.getZ();
				noclipPrimed = true;
			}
			noclipX += vx;
			noclipY += vy;
			noclipZ += vz;
			boat.setPos(noclipX, noclipY, noclipZ);
			// The rider is positioned from the hull at the start of vanilla's
			// tick, before this step. Moving the passenger by the same delta
			// keeps the body (and the camera in third person) attached instead
			// of trailing one tick behind the boat.
			if (player.isPassenger() && player.getVehicle() == boat) {
				player.setPos(player.getX() + vx, player.getY() + vy, player.getZ() + vz);
			}
			boat.setDeltaMovement(0.0, 0.0, 0.0);
			if (!boat.noPhysics) {
				boat.noPhysics = true;
			}
		} else {
			// The boat's own tick subtracts its gravity every tick, before it
			// moves. Adding it back makes the fall speed the slider's speed.
			// In water the buoyancy step already balances gravity, and on the
			// ground the collision already stops the fall - compensating there
			// would push the hull up out of its resting place.
			double buoyancy = boat.isInWater() || boat.onGround() ? 0.0 : boat.getGravity();
			boat.setDeltaMovement(vx, vy + buoyancy, vz);
		}

		// The camera steers the boat: without this the hull keeps whatever
		// heading it last had and the flight reads as sidestepping.
		boat.setYRot(player.getYRot());
		boat.setYHeadRot(player.getYRot());
		boat.resetFallDistance();
	}

	@Override
	protected void onToggle(boolean state) {
		release();
	}

	/** Restores the boat's collision and forgets it. */
	protected void release() {
		noclipPrimed = false;
		if (controlled != null) {
			if (noclip) {
				controlled.noPhysics = false;
			}
			controlled = null;
		}
	}

	// ---------------------------------------------------------- persistence

	@Override
	public void loadConfig(JsonObject json) {
		horizontalSpeed.set(ConfigValues.intValue(json, "horizontal_speed", 100));
		verticalSpeed.set(ConfigValues.intValue(json, "vertical_speed", 80));
		boatFall.set(ConfigValues.intValue(json, "boat_fall", 100));
	}

	@Override
	public void saveConfig(JsonObject json) {
		json.addProperty("horizontal_speed", horizontalSpeed.get());
		json.addProperty("vertical_speed", verticalSpeed.get());
		json.addProperty("boat_fall", boatFall.get());
	}
}
