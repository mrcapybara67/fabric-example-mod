package com.capy.client.module.modules;

import com.capy.client.config.ConfigValues;
import com.capy.client.mixin.MouseHandlerAccessor;
import com.capy.client.module.BooleanSetting;
import com.capy.client.module.Category;
import com.capy.client.module.Configurable;
import com.capy.client.module.Module;
import com.capy.client.module.SliderSetting;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;

/**
 * Smooth Camera — the view eases into mouse movement instead of snapping to it.
 *
 * How it works: every frame the mouse delta is split in two. One part is applied
 * now, the rest is banked and released over the following frames, so a fast
 * flick becomes a short glide and small corrections still feel immediate. The
 * bank is what makes this smoothing rather than a sensitivity slider: movement
 * is never lost, only spread over a few frames.
 *
 * The response per frame is derived from the frame time, so the same slider feels
 * the same at 60 and at 240 frames per second: {@code response = 1 - smoothing^frames}.
 * At the default 0.60 the camera reaches about 40% of a movement per 60th of a
 * second, and the high end (0.95) is a heavy, almost cinematic trail.
 *
 * The delta is rewritten in {@code MouseHandler.handleAccumulatedMovement}, so
 * vanilla still turns the player: aiming, hit detection and the third person
 * camera all follow the view exactly.
 */
public class SmoothCameraModule extends Module implements Configurable {
	public SmoothCameraModule() {
		super(Category.VISUAL, "smooth_camera");
	}

	private final SliderSetting smoothing = setting(new SliderSetting("smoothing", 60, 5, 95, 2));
	private final SliderSetting speed = setting(new SliderSetting("speed", 10, 5, 30, 1));
	private final BooleanSetting cinematic = setting(new BooleanSetting("cinematic", false));

	/** Movement that has been read from the mouse but not applied yet. */
	private double bankX;
	private double bankY;
	private long lastNanos;
	/** True while the module is the one that switched vanilla's cinematic camera on. */
	private boolean cinematicApplied;
	private boolean cinematicWasOn;

	@Override
	protected void onToggle(boolean state) {
		Minecraft mc = Minecraft.getInstance();
		// Guard: getInstance() exists from the very first initialiser, but the
		// game has not built its options yet at that point, so mc.options is
		// null while ClientConfig.load() re-enables the modules inside
		// onInitializeClient(). Reading mc.options.smoothCamera there is the
		// startup crash. Returning leaves the module flagged as enabled: the
		// state above is never touched, and syncCinematic() applies the setting
		// on the first frame, when the options exist.
		if (mc == null || mc.options == null) {
			return;
		}
		if (state) {
			cinematicWasOn = mc.options.smoothCamera;
			return;
		}
		bankX = 0.0;
		bankY = 0.0;
		lastNanos = 0L;
		cinematicApplied = false;
		mc.options.smoothCamera = cinematicWasOn;
	}

	/**
	 * Called at the head of the frame's mouse handling.
	 *
	 * Reads the accumulated delta, applies this frame's share, and writes the
	 * share back so vanilla turns the player by exactly that amount.
	 */
	public void applyTo(MouseHandler handler) {
		syncCinematic();
		Minecraft mc = Minecraft.getInstance();
		MouseHandlerAccessor mouse = (MouseHandlerAccessor) (Object) handler;
		if (mc.player == null || mc.screen != null || !handler.isMouseGrabbed() || !mc.isWindowActive()) {
			// A menu, a lost grab, an unfocused window or leaving the world must
			// not leave movement banked: releasing it later would turn the
			// player with no input.
			bankX = 0.0;
			bankY = 0.0;
			lastNanos = 0L;
			return;
		}

		long now = System.nanoTime();
		float dt = lastNanos == 0L ? 1f / 60f : (now - lastNanos) / 1.0e9f;
		lastNanos = now;
		dt = Math.max(0.002f, Math.min(0.1f, dt));

		// Response of an exponential average, resolved to the actual frame time.
		float response = (float) (1.0 - Math.pow(smoothing.real(), dt * 60.0));
		double multiplier = speed.real();

		double targetX = bankX + mouse.capy$accumulatedDX() * multiplier;
		double targetY = bankY + mouse.capy$accumulatedDY() * multiplier;
		double stepX = targetX * response;
		double stepY = targetY * response;
		bankX = targetX - stepX;
		bankY = targetY - stepY;

		mouse.capy$setAccumulatedDX(stepX);
		mouse.capy$setAccumulatedDY(stepY);
	}

	/** Applies the cinematic camera setting once, whenever it changes. */
	public void syncCinematic() {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null) {
			return;
		}
		boolean wanted = cinematic.value();
		if (wanted != cinematicApplied) {
			mc.options.smoothCamera = wanted;
			cinematicApplied = wanted;
		}
	}

	// ---------------------------------------------------------- persistence

	@Override
	public void loadConfig(JsonObject json) {
		smoothing.set(ConfigValues.intValue(json, "smoothing", 60));
		speed.set(ConfigValues.intValue(json, "speed", 10));
		cinematic.set(ConfigValues.boolValue(json, "cinematic", false));
	}

	@Override
	public void saveConfig(JsonObject json) {
		json.addProperty("smoothing", smoothing.get());
		json.addProperty("speed", speed.get());
		json.addProperty("cinematic", cinematic.value());
	}
}
