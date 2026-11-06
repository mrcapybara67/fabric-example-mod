package com.capy.client.module.modules;

/**
 * Boat Noclip — boat flight with collision removed.
 *
 * <p>It is the same module as {@link BoatFlyModule}, with one difference: the
 * hull is stepped directly. Setting {@code noPhysics} alone was not enough -
 * the boat's own tick still resolves its movement against the world before the
 * module runs, so a wall could move the hull back even with the flag on. This
 * variant keeps its own position, advances it by the requested step, and writes
 * it back every tick, so the client's collision solver never sees the move to
 * reject it. The flag is kept on as a second line of defence for vanilla's
 * passenger and packet code.</p>
 *
 * <p>Everything else - the speed sliders, the boat-fall slider, the block on the
 * paddle input, the ground flag on the vehicle packet, the restoration when the
 * module or the ride ends - is inherited unchanged.</p>
 *
 * <p>This is client-side. A server still sees a boat whose position updates by
 * the same distance per packet as any other moving boat; whether it accepts a
 * boat inside a wall is the server's business, not this flag's.</p>
 */
public class BoatNoclipModule extends BoatFlyModule {
	public BoatNoclipModule() {
		super("boat_noclip", true);
	}
}
