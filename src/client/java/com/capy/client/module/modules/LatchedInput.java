package com.capy.client.module.modules;

import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * A real ClientInput object held by the player while Freecam is detached.
 *
 * <p>Its tick restores the snapshot the player had when the body was latched.
 * The player's own {@code KeyboardInput} overrides {@code ClientInput.tick},
 * which is why replacing the object is the only reliable way to keep the body
 * moving exactly as it was before the camera left.</p>
 */
public final class LatchedInput extends ClientInput {
	private final Input keys;
	private final Vec2 move;

	public LatchedInput(Input keys, Vec2 move) {
		this.keys = keys == null ? Input.EMPTY : keys;
		this.move = move == null ? Vec2.ZERO : move;
	}

	@Override
	public void tick() {
		this.keyPresses = keys;
		this.moveVector = move;
	}
}
