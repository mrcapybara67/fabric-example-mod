package com.capy.client.gui;

import com.capy.client.config.ClientConfig;
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
 * The transition is a short fade with a small rise and a slight scale - it
 * must never delay the interface, and it must never leave the screen stuck
 * in a state where it cannot be closed again.
 */
public class ClickGuiScreen extends Screen {
	/** The typefaces are created once and reused for every frame. */
	private static final UiType UI_TYPE = new McType();

	private final GuiFrame frame = new GuiFrame();
	private final Anim open = new Anim(0f, 11f);
	private boolean closing;

	public ClickGuiScreen() {
		super(Component.translatable("capy.gui.title"));
	}

	@Override
	protected void init() {
		frame.rebuild();
		frame.resize(this.width, this.height);
		closing = false;
		open.snap(0f);
	}

	@Override
	public void resize(int width, int height) {
		super.resize(width, height);
		this.width = width;
		this.height = height;
		frame.resize(width, height);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		open.to(closing ? 0f : 1f, partialTick);
		float progress = open.value();
		if (progress <= 0.015f) {
			if (closing) {
				finishClose();
			}
			return;
		}
		frame.render(McCanvas.of(g), UI_TYPE, mouseX, mouseY, partialTick, progress);
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
		if (!closing) {
			closing = true;
		}
	}

	/** Called after the outro finished. */
	private void finishClose() {
		ClientConfig.saveNow();
		super.onClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
