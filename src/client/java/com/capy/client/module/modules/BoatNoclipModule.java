package com.capy.client.module.modules;

/**
 * Boat Noclip — boat flight with collision removed.
 *
 * <p>It is the same module as {@link BoatFlyModule}, with one difference: the
 * boat's {@code noPhysics} flag is held on while it is being flown, which is
 * the flag vanilla's own movement solver checks before it resolves a move
 * against the world. The boat therefore travels in a straight line through
 * terrain instead of stopping at it. Everything else - the speed sliders, the
 * block on the paddle input, the ground flag on the vehicle packet, the
 * restoration when the module or the ride ends - is inherited unchanged.</p>
 *
 * <p>The flag is client-side only. A server still sees a boat whose position
 * updates by the same distance per packet as any other moving boat; whether
 * it accepts a boat inside a wall is the server's business, not this flag's.
 * The module is turned off, or the player dismounts, and the collision flag is
 * cleared on the boat that was being flown.</p>
 */
public class BoatNoclipModule extends BoatFlyModule {
	public BoatNoclipModule() {
		super("boat_noclip", true);
	}
}
