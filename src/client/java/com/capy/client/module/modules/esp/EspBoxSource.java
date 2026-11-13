package com.capy.client.module.modules.esp;

import java.util.List;
import net.minecraft.world.phys.AABB;

/** A module that contributes boxes to the shared world overlay renderer. */
public interface EspBoxSource {
	List<AABB> boxes();

	int colorFor(AABB box);

	boolean fills();

	boolean outlines();

	boolean tracers();

	float boxWidth();

	float tracerWidth();
}
