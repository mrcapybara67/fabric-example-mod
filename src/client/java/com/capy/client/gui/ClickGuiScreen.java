package com.capy.client.gui;

import com.capy.client.config.ClientConfig;
import com.capy.client.keybind.ClientKeybinds;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The Minecraft screen that hosts the Capy window.
 *
 * The screen itself is deliberately thin: it owns the open and close
 * transition, forwards input to {@link GuiFrame} and keeps the game in sync
 * with the window. All layout and drawing lives in the frame, which is what
 * makes the interface reviewable outside the game.
 *
 * The transition is clock driven: {@code progress} is computed from wall time,
 * so the timing never depends on the frame rate, and the outro finishes even if
 * a frame is late. Closing is shorter than opening on purpose - leaving an
 * interface should never feel like waiting.
 *
 * The close is committed from {@link #tick()}, never from {@code render()}.
 * Two things follow from that, and both matter:
 *
 *  - {@code Minecraft.setScreen(null)} replaces the screen, releases every key
 *    mapping and grabs the mouse again. Doing that from the middle of a frame
 *    means the rest of that frame still runs against a screen the game has
 *    already detached. Vanilla itself only ever closes a screen from input
 *    handling or from a tick; committing from the tick puts this screen on the
 *    same path as pressing Escape in a vanilla menu.
 *  - A close that only advances while frames are drawn can stall: a screen
 *    that is set but no longer advances its outro keeps the world frozen
 *    behind an invisible window - the camera does not turn and clicks are
 *    swallowed - until another screen is opened and closed. Driving the
 *    timeline from wall time and committing it from the tick removes that
 *    failure mode, because ticks always run while the screen is set.
 *
 * The screen must never get stuck in a state where it cannot be closed again,
 * so the progress is clamped at both ends, the close is idempotent and the
 * game's input state is restored explicitly afterwards.
 */
public class ClickGuiScreen extends Screen {
	/** The typefaces are created once and reused for every frame. */
	private static final UiType UI_TYPE = new McType();

	/** Seconds the window takes to open. */
	private static final float OPEN_SECONDS = 0.26f;
	/** Seconds the window takes to close. Shorter: leaving should feel instant. */
	private static final float CLOSE_SECONDS = 0.17f;

	private static final float NANOS_PER_SECOND = 1.0e9f;

	private final GuiFrame frame = new GuiFrame();

	/** When the intro started, in {@link System#nanoTime()} units. */
	private long shownAt;
	/** When the outro started, or 0 while the window is open. */
	private long closingAt;
	/** The progress the outro starts from, so closing early does not jump. */
	private float closingFrom = 1f;
	/** True once the screen is really closed; keeps the close idempotent. */
	private boolean finished;

	/** When the previous frame was drawn, in {@link System#nanoTime()} units. */
	private long lastFrameAt;

	/** One tick of animation time, in nanoseconds (twenty ticks a second). */
	private static final float NANOS_PER_TICK = 50_000_000f;

	public ClickGuiScreen() {
		super(Component.translatable("capy.gui.title"));
	}

	@Override
	protected void init() {
		frame.rebuild();
		frame.resize(this.width, this.height);
		// Deliberately not resetting the transition here: a resize re-enters the
		// layout, and it must not restart the intro or cancel an outro that is
		// already playing - a cancelled outro is a screen that stays open while
		// it draws nothing.
		if (shownAt == 0L) {
			shownAt = System.nanoTime();
		}
	}

	@Override
	public void resize(int width, int height) {
		super.resize(width, height);
		frame.resize(width, height);
	}

	/** 0 closed .. 1 open, from wall time, so the timing is frame rate free. */
	private float progress() {
		long now = System.nanoTime();
		if (closingAt == 0L) {
			return clamp((now - shownAt) / (OPEN_SECONDS * NANOS_PER_SECOND));
		}
		float elapsed = (now - closingAt) / (CLOSE_SECONDS * NANOS_PER_SECOND);
		return clamp(closingFrom * (1f - elapsed));
	}

	private static float clamp(float value) {
		return Math.max(0f, Math.min(1f, value));
	}

	@Override
	public void tick() {
		// The clock, not an animation counter, decides when the outro is over,
		// so a missed or late frame cannot leave the screen open forever.
		if (!finished && closingAt != 0L
				&& (System.nanoTime() - closingAt) >= CLOSE_SECONDS * NANOS_PER_SECOND) {
			finishClose();
		}
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		if (finished) {
			return;
		}
		float progress = progress();
		if (progress <= 0f) {
			return;
		}
		// The frame's own elapsed time, in ticks. partialTick is not a delta:
		// it cycles 0..1 between ticks, so passing it to the animation code made
		// every transition stall on the frames where it happened to be zero and
		// run at a different speed on every refresh rate. The wall clock is the
		// only value that makes the easing frame rate independent, which is what
		// {@link Anim} and {@link Motion} already assume.
		frame.render(McCanvas.of(g), UI_TYPE, mouseX, mouseY, frameDeltaTicks(), progress);
	}

	/**
	 * Time since the last frame in ticks, clamped so a long pause (a world load,
	 * a minimised window) cannot make the whole interface jump in one frame.
	 */
	private float frameDeltaTicks() {
		long now = System.nanoTime();
		float ticks = lastFrameAt == 0L ? 1.0f : (now - lastFrameAt) / NANOS_PER_TICK;
		lastFrameAt = now;
		return Math.max(0.05f, Math.min(4.0f, ticks));
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		boolean handled = frame.mouseClicked(event, doubled);
		if (frame.closeRequested()) {
			onClose();
		}
		return handled;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		return frame.mouseDragged(event.x(), event.y());
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		return frame.mouseReleased(event.x(), event.y());
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double xAmount, double yAmount) {
		return frame.mouseScrolled(mouseX, mouseY, yAmount);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		return frame.charTyped(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (frame.keyPressed(event)) {
			if (frame.closeRequested()) {
				onClose();
			}
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		// The frame asks for the close, the screen plays the outro.
		if (finished || closingAt != 0L) {
			return;
		}
		closingFrom = progress();
		closingAt = System.nanoTime();
	}

	/**
	 * True once the outro has finished and the screen has closed itself.
	 *
	 * The tick listener checks this and detaches the screen if it is somehow
	 * still attached. That is the last line of defence against the one failure
	 * mode this screen must never have: a screen that paints nothing but keeps
	 * swallowing every key and click, which the player experiences as a frozen
	 * game.
	 */
	public boolean isFinished() {
		return finished;
	}

	/** Called once, from the tick, after the outro finished. */
	private void finishClose() {
		if (finished) {
			return;
		}
		finished = true;
		ClientConfig.saveNow();
		super.onClose();
		// Vanilla re-grabs the mouse and restores the *toggle* keys, but it does
		// not read a held movement key back into its mapping, and it skips the
		// mouse entirely when the window is not focused. The restore is done
		// from the tick, where it can be retried until it actually worked.
		ClientKeybinds.requestInputRestore();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
