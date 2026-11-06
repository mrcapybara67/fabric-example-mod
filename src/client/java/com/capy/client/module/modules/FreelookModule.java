package com.capy.client.module.modules;

import com.capy.client.config.ConfigValues;
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
 * Freelook — a third-person orbit that leaves the body and its heading alone.
 *
 * <p>This is the companion of Freecam, not a copy of it. Freecam detaches the
 * camera and lets it fly away; Freelook keeps it on a short leash around the
 * head, turns it with the mouse, and lets the player walk, mine and fight with
 * the body facing wherever it was already facing. The camera is placed behind
 * the orbit direction so the body stays in frame, which is the view players
 * expect from a freelook key.</p>
 *
 * <p>The orbit is only a camera. No packet is sent, the server never sees a
 * second entity, and the body's own look packets keep the heading the player
 * had before the module was enabled. That also means the module cannot be used
 * to see around a corner while mining: interaction still uses the body's real
 * view direction, exactly as the server understands it.</p>
 */
public final class FreelookModule extends Module implements Tickable, Configurable {
	private static final float MIN_PITCH = -85f;
	private static final float MAX_PITCH = 85f;

	/** Orbit radius, in tenths of a block (40 = 4.0 blocks). */
	private final SliderSetting distance = setting(new SliderSetting("distance", 40, 15, 80, 1));
	private final SliderSetting height = setting(new SliderSetting("height", 2, -10, 15, 1));
	private final SliderSetting sensitivity = setting(new SliderSetting("sensitivity", 100, 20, 300));
	private float yaw;
	private float pitch;
	private boolean primed;
	private Level lastLevel;

	public FreelookModule() {
		super(Category.VISUAL, "freelook");
	}

	public boolean isActive() {
		return isEnabled() && primed;
	}

	@Override
	protected void onToggle(boolean state) {
		primed = false;
		lastLevel = null;
	}

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
		if (!primed || mc.level != lastLevel) {
			prime(player);
		}
		// The body must not inherit the orbit. LocalPlayer.turn is what applies
		// vanilla's mouse delta; the mouse mixin cancels that path while this is
		// active, so the body keeps the heading it had when the key was pressed.
	}

	private void prime(LocalPlayer player) {
		lastLevel = player.level();
		yaw = player.getYRot();
		pitch = player.getXRot();
		primed = true;
	}

	/**
	 * Places the camera for this frame.
	 *
	 * @return true when the camera was placed and vanilla's placement may be
	 *         overwritten
	 */
	public boolean updateCamera(Minecraft mc, float partialTick) {
		if (!isActive() || mc == null || mc.player == null) {
			return false;
		}
		LocalPlayer player = mc.player;
		if (player.isDeadOrDying() || player.isSleeping() || player.isRemoved()) {
			setEnabled(false);
			return false;
		}
		Vec3 eye = player.getEyePosition(partialTick);
		float yawRadians = yaw * 0.017453292F;
		float pitchRadians = pitch * 0.017453292F;
		float cosPitch = (float) Math.cos(pitchRadians);
		double fx = -Math.sin(yawRadians) * cosPitch;
		double fy = -Math.sin(pitchRadians);
		double fz = Math.cos(yawRadians) * cosPitch;
		double radius = distance.real();
		cameraX = eye.x - fx * radius;
		cameraY = eye.y - fy * radius + height.real();
		cameraZ = eye.z - fz * radius;
		return true;
	}

	private double cameraX;
	private double cameraY;
	private double cameraZ;

	public Vec3 cameraPosition() {
		return new Vec3(cameraX, cameraY, cameraZ);
	}

	public float cameraYaw() {
		return yaw;
	}

	public float cameraPitch() {
		return pitch;
	}

	/** Steers the orbit from one frame's accumulated mouse delta. */
	public void applyLook(double deltaX, double deltaY) {
		if (deltaX == 0.0 && deltaY == 0.0) {
			return;
		}
		float factor = (float) sensitivity.real() / 100f;
		yaw = wrapDegrees(yaw + (float) (deltaX * factor));
		pitch = Math.max(MIN_PITCH, Math.min(MAX_PITCH, pitch + (float) (deltaY * factor)));
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

	// ---------------------------------------------------------- persistence

	@Override
	public void loadConfig(JsonObject json) {
		distance.set(ConfigValues.intValue(json, "distance", 40));
		height.set(ConfigValues.intValue(json, "height", 2));
		sensitivity.set(ConfigValues.intValue(json, "sensitivity", 100));
	}

	@Override
	public void saveConfig(JsonObject json) {
		json.addProperty("distance", distance.get());
		json.addProperty("height", height.get());
		json.addProperty("sensitivity", sensitivity.get());
	}
}
