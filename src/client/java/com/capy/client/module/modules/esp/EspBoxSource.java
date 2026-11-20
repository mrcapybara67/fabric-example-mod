package com.capy.client.module.modules.esp;

import java.util.List;
import net.minecraft.world.phys.AABB;

/** A module that contributes boxes to the shared world overlay renderer. */
public interface EspBoxSource {
	List<AABB> boxes();

	int colorFor(AABB box);

	/** Frame-stable colour for an animated setting. */
	default int colorFor(AABB box, double timeSec) {
		return colorFor(box);
	}

	boolean fills();

	boolean outlines();

	boolean tracers();

	float boxWidth();

	float tracerWidth();

	/** False draws with depth testing, so terrain can occlude the overlay. */
	default boolean throughWalls() {
		return true;
	}

	/** True fades a tracer towards its far end instead of drawing it flat. */
	default boolean tracerFade() {
		return false;
	}
}
