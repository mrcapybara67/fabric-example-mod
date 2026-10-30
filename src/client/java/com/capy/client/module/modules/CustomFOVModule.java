package com.capy.client.module.modules;

import com.capy.client.config.ConfigValues;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.Category;
import com.capy.client.module.Configurable;
import com.capy.client.module.Module;
import com.capy.client.module.SliderSetting;
import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.LivingEntity;

/**
 * Custom FOV — one field of view per movement state, eased between them.
 *
 * Priority, highest first: elytra, creative flight, sprinting, combat, default.
 * Sprinting does not stack with flying, and gliding wins over everything because
 * it is the one state where the player is looking at the ground at speed.
 *
 * The value is produced inside {@code GameRenderer.getFov}, on the frame the
 * projection is built, so the smoothing is per frame rather than per tick: a
 * 240 Hz view eases just as smoothly as a 60 Hz one. "Transition Speed" is how
 * much of the remaining difference is closed per second — 4.0 reaches a new
 * field of view in about a quarter of a second, 0.5 drifts into it over two.
 *
 * Combat counts as "weapon in hand and something alive within ten blocks".
 * Nothing about the target is sent anywhere; the check only reads the client's
 * own entity list.
 */
public class CustomFOVModule extends Module implements Configurable {
	private static final float MIN_FOV = 30f;
	private static final float MAX_FOV = 150f;
	/** The field of view the projection was last built with, for the sky. */
	private static volatile float lastFov = 70f;

	private final SliderSetting defaultFov = setting(new SliderSetting("default_fov", 70, 30, 150));
	private final SliderSetting sprintFov = setting(new SliderSetting("sprint_fov", 85, 30, 150));
	private final SliderSetting flyFov = setting(new SliderSetting("fly_fov", 90, 30, 150));
	private final SliderSetting elytraFov = setting(new SliderSetting("elytra_fov", 110, 30, 150));
	private final SliderSetting combatFov = setting(new SliderSetting("combat_fov", 65, 30, 150));
	private final SliderSetting transition = setting(new SliderSetting("transition_speed", 40, 5, 100, 1));
	private final BooleanSetting overrideEffects = setting(new BooleanSetting("override_effects", true));

	private float currentFov = 70f;
	private long lastNanos;
	/** Combat state and when it was last read. */
	private boolean inCombat;
	private long lastCombatCheck;

	public CustomFOVModule() {
		super(Category.VISUAL, "custom_fov");
	}

	/** The field of view the current frame was projected with. */
	public static float lastFov() {
		return lastFov;
	}

	/** Remembers the field of view vanilla (or this module) settled on. */
	public static void recordFov(float fov) {
		if (fov > 1f && fov < 180f) {
			lastFov = fov;
		}
	}

	/** True when speed and potion effects are not allowed to widen the view. */
	public boolean overridesEffects() {
		return overrideEffects.value();
	}

	/** The field of view for this frame, already eased towards its target. */
	public float frameFov() {
		float target = targetFov(Minecraft.getInstance());
		long now = System.nanoTime();
		float dt = lastNanos == 0L ? 1f / 60f : (now - lastNanos) / 1.0e9f;
		lastNanos = now;
		dt = Math.max(0.002f, Math.min(0.1f, dt));
		float step = (float) Math.min(1.0, transition.real() * dt);
		currentFov += (target - currentFov) * step;
		currentFov = Math.max(MIN_FOV, Math.min(MAX_FOV, currentFov));
		return currentFov;
	}

	/** The state the player is in decides the field of view it should have. */
	private float targetFov(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null) {
			return defaultFov.get();
		}
		if (player.isFallFlying()) {
			return elytraFov.get();
		}
		if (player.getAbilities().flying) {
			return flyFov.get();
		}
		if (player.isSprinting()) {
			return sprintFov.get();
		}
		if (combat(mc, player)) {
			return combatFov.get();
		}
		return defaultFov.get();
	}

	/**
	 * Combat state, re-read ten times a second.
	 *
	 * {@code getFov} runs once per frame, and early in the frame: an entity
	 * query over a twenty block cube at 240 fps is a scan the frame does not
	 * need, because whether something is within ten blocks cannot change between
	 * one frame and the next. Ten checks a second is far finer than the quarter
	 * of a second the view takes to ease anyway.
	 */
	private boolean combat(Minecraft mc, LocalPlayer player) {
		long now = System.nanoTime();
		if (now - lastCombatCheck < 100_000_000L) {
			return inCombat;
		}
		lastCombatCheck = now;
		inCombat = inCombat(mc, player);
		return inCombat;
	}

	/** A weapon in the main hand and a living target inside ten blocks. */
	private static boolean inCombat(Minecraft mc, LocalPlayer player) {
		if (mc.level == null || !player.getMainHandItem().has(DataComponents.WEAPON)) {
			return false;
		}
		List<LivingEntity> nearby = mc.level.getEntitiesOfClass(LivingEntity.class,
				player.getBoundingBox().inflate(10.0), entity -> entity != player && entity.isAlive());
		return !nearby.isEmpty();
	}

	@Override
	protected void onToggle(boolean state) {
		if (!state) {
			// Start from the field of view the player actually has next time,
			// so switching the module on never snaps the view.
			currentFov = lastFov;
			lastNanos = 0L;
		}
	}

	// ---------------------------------------------------------- persistence

	@Override
	public void loadConfig(JsonObject json) {
		defaultFov.set(ConfigValues.intValue(json, "default_fov", 70));
		sprintFov.set(ConfigValues.intValue(json, "sprint_fov", 85));
		flyFov.set(ConfigValues.intValue(json, "fly_fov", 90));
		elytraFov.set(ConfigValues.intValue(json, "elytra_fov", 110));
		combatFov.set(ConfigValues.intValue(json, "combat_fov", 65));
		transition.set(ConfigValues.intValue(json, "transition_speed", 40));
		overrideEffects.set(ConfigValues.boolValue(json, "override_effects", true));
	}

	@Override
	public void saveConfig(JsonObject json) {
		json.addProperty("default_fov", defaultFov.get());
		json.addProperty("sprint_fov", sprintFov.get());
		json.addProperty("fly_fov", flyFov.get());
		json.addProperty("elytra_fov", elytraFov.get());
		json.addProperty("combat_fov", combatFov.get());
		json.addProperty("transition_speed", transition.get());
		json.addProperty("override_effects", overrideEffects.value());
	}
}
